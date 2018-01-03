package com.twitter.finagle.memcached.integration

import com.twitter.conversions.time._
import com.twitter.finagle._
import com.twitter.finagle.client.StackClient
import com.twitter.finagle.liveness.FailureAccrualFactory
import com.twitter.finagle.loadbalancer.LoadBalancerFactory
import com.twitter.finagle.memcached.loadbalancer.ConcurrentLoadBalancerFactory
import com.twitter.finagle.memcached.partitioning.MemcachedPartitioningService
import com.twitter.finagle.memcached.protocol.{Command, Response}
import com.twitter.finagle.memcached.{Client, TwemcacheClient}
import com.twitter.finagle.naming.BindingFactory
import com.twitter.finagle.service.TimeoutFilter
import com.twitter.finagle.stats.InMemoryStatsReceiver
import com.twitter.hashing.KeyHasher
import com.twitter.io.Buf
import com.twitter.util._
import java.net.InetSocketAddress
import scala.util.Random

class MemcachedPartitioningClientTest extends MemcachedTest {

  protected[this] val isOldClient: Boolean = false

  protected def createClient(dest: Name, clientName: String): Client = {
    newClient(dest)
  }

  protected[this] val redistributesKey: Seq[String] =
    Seq("test_client", "partitioner", "redistributes")
  protected[this] val leavesKey: Seq[String] = Seq(clientName, "partitioner", "leaves")
  protected[this] val revivalsKey: Seq[String] = Seq(clientName, "partitioner", "revivals")
  protected[this] val ejectionsKey: Seq[String] = Seq(clientName, "partitioner", "ejections")

  private[this] def newClientStack(): Stack[ServiceFactory[Command, Response]] = {
    // create a partitioning aware finagle client by inserting the PartitioningService appropriately
    StackClient
      .newStack[Command, Response]
      .replace(LoadBalancerFactory.role, ConcurrentLoadBalancerFactory.module[Command, Response])
      .insertAfter(
        BindingFactory.role,
        MemcachedPartitioningService.module
      )
  }

  private[this] def newClient(dest: Name, label: String = clientName) = {
    TwemcacheClient(
      Memcached.client
        .configured(Memcached.param.KeyHasher(KeyHasher.KETAMA))
        .configured(TimeoutFilter.Param(10000.milliseconds))
        .configured(Memcached.param.EjectFailedHost(false))
        .withStack(newClientStack())
        .newService(dest, label)
    )
  }

  test("re-hash when a bad host is ejected") {
    val sr = new InMemoryStatsReceiver
    val client = TwemcacheClient(
      Memcached.client
        .configured(Memcached.param.KeyHasher(KeyHasher.FNV1_32))
        .configured(TimeoutFilter.Param(10000.milliseconds))
        .configured(Memcached.param.EjectFailedHost(true))
        .configured(FailureAccrualFactory.Param(1, () => 10.minutes))
        .configured(param.Stats(sr))
        .withStack(newClientStack())
        .newService(Name.bound(servers.map { s =>
          Address(s.address)
        }: _*), clientName)
    )
    testRehashUponEject(client, sr)
    client.close()
  }

  test("host comes back into ring after being ejected") {
    testRingReEntryAfterEjection(
      (timer, cacheServer, statsReceiver) =>
        TwemcacheClient(
          Memcached.client
            .configured(Memcached.param.KeyHasher(KeyHasher.FNV1_32))
            .configured(TimeoutFilter.Param(10000.milliseconds))
            .configured(FailureAccrualFactory.Param(1, () => 10.minutes))
            .configured(Memcached.param.EjectFailedHost(true))
            .configured(param.Timer(timer))
            .configured(param.Stats(statsReceiver))
            .withStack(newClientStack())
            .newService(
              Name.bound(Address(cacheServer.boundAddress.asInstanceOf[InetSocketAddress])),
              clientName
            )
      )
    )
  }

  test("Add and remove nodes") {
    val addrs = servers.map { s =>
      Address(s.address)
    }

    // Start with 3 backends
    val mutableAddrs: ReadWriteVar[Addr] = new ReadWriteVar(Addr.Bound(addrs.toSet.drop(2)))

    val sr = new InMemoryStatsReceiver

    val client = TwemcacheClient(
      Memcached.client
        .configured(Memcached.param.KeyHasher(KeyHasher.FNV1_32))
        .configured(TimeoutFilter.Param(10000.milliseconds))
        .configured(FailureAccrualFactory.Param(1, () => 10.minutes))
        .configured(Memcached.param.EjectFailedHost(true))
        .connectionsPerEndpoint(NumConnections)
        .withStack(newClientStack())
        .withStatsReceiver(sr)
        .newService(
          Name.Bound.singleton(mutableAddrs),
          clientName
        )
    )
    testAddAndRemoveNodes(addrs, mutableAddrs, sr)
    client.close()
  }

  test("FailureAccrualFactoryException has remote address") {
    val client = TwemcacheClient(
      Memcached.client
        .configured(Memcached.param.KeyHasher(KeyHasher.FNV1_32))
        .configured(TimeoutFilter.Param(10000.milliseconds))
        .configured(FailureAccrualFactory.Param(1, 10.minutes))
        .configured(Memcached.param.EjectFailedHost(false))
        .connectionsPerEndpoint(1)
        .withStack(newClientStack())
        .newService(Name.bound(Address("localhost", 1234)), clientName)
    )
    testFailureAccrualFactoryExceptionHasRemoteAddress(client)
    client.close()
  }

  // migration test
  test("data read/write consistency between old and new clients") {
    val numKeys = 20
    val keyLength = 50
    val suffix = ":" + Time.now.inSeconds

    def randomString(length: Int): String = {
      Random.alphanumeric.take(length).mkString
    }

    def writeKeys(client: Client): Seq[String] = {
      // creating multiple random strings so that we get a uniform distribution of keys the
      // ketama ring and thus the Memcached shards
      val keys = 1 to numKeys map { _ =>
        randomString(keyLength)
      }
      val writes = keys map { key =>
        client.set(key, Buf.Utf8(s"$key" + suffix))
      }
      awaitResult(Future.join(writes))
      keys
    }

    def assertRead(client: Client, keys: Seq[String]): Unit = {
      val readValues: Map[String, Buf] = awaitResult { client.get(keys) }
      assert(readValues.size == keys.length)
      assert(readValues.keySet.toSeq.sorted == keys.sorted)
      readValues.keys foreach { k =>
        val Buf.Utf8(readValue) = readValues(k)
        assert(readValue == k + suffix)
      }
    }

    val newClient = client
    val oldClient = {
      val dest = Name.bound(servers.map { s =>
        Address(s.address)
      }: _*)
      Memcached.client.newRichClient(dest, clientName)
    }

    // make sure old and new client can read the values written by old client
    val keys1 = writeKeys(oldClient)
    assertRead(oldClient, keys1)
    assertRead(newClient, keys1)

    // make sure old and new client can read the values written by new client
    val keys2 = writeKeys(newClient)
    assertRead(oldClient, keys2)
    assertRead(newClient, keys2)
  }
}
