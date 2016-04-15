/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.NANOSECONDS
import scala.concurrent.duration._
import akka.actor._
import akka.remote.RemoteActorRefProvider
import akka.remote.testconductor.RoleName
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.remote.testkit.STMultiNodeSpec
import akka.testkit._
import com.typesafe.config.ConfigFactory
import java.net.InetAddress

object MaxThroughputSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")

  val hostname =
    if (java.lang.Boolean.getBoolean("akka.test.multi-node"))
      InetAddress.getLocalHost.getHostName
    else
      "localhost"

  commonConfig(debugConfig(on = false).withFallback(
    ConfigFactory.parseString(s"""
       MaxThroughputSpec.totalMessagesFactor = 1.0
       akka {
         loglevel = INFO
         testconductor.barrier-timeout = 120s
         actor {
           provider = "akka.remote.RemoteActorRefProvider"
           serialize-creators = false
           serialize-messages = false
         }
         remote.artery {
           enabled = on
           hostname = "$hostname"
         }
       }
       """)))

  def aeronPort(roleName: RoleName): Int =
    roleName match {
      case `first`  ⇒ 20501 // TODO yeah, we should have support for dynamic port assignment
      case `second` ⇒ 20502
    }

  nodeConfig(first) {
    ConfigFactory.parseString(s"""
      akka.remote.artery.port = ${aeronPort(first)}
      """)
  }

  nodeConfig(second) {
    ConfigFactory.parseString(s"""
      akka.remote.artery.port = ${aeronPort(second)}
      """)
  }

  case object Run
  sealed trait Echo extends DeadLetterSuppression
  final case object Start extends Echo
  final case object End extends Echo
  final case class EndResult(totalReceived: Long)
  final case class FlowControl(burstStartTime: Long) extends Echo

  def receiverProps(reporter: RateReporter, payloadSize: Int): Props =
    Props(new Receiver(reporter, payloadSize))

  class Receiver(reporter: RateReporter, payloadSize: Int) extends Actor {
    var c = 0L

    def receive = {
      case Start ⇒
        c = 0
        sender() ! Start
      case End ⇒
        sender() ! EndResult(c)
        context.stop(self)
      case m: Echo ⇒
        sender() ! m
      case _ ⇒
        reporter.onMessage(1, payloadSize)
        c += 1
    }
  }

  def senderProps(target: ActorRef, testSettings: TestSettings): Props =
    Props(new Sender(target, testSettings))

  class Sender(target: ActorRef, testSettings: TestSettings) extends Actor {
    import testSettings._
    val payload = ("0" * testSettings.payloadSize).getBytes("utf-8")
    var startTime = 0L
    var remaining = totalMessages
    var maxRoundTripMillis = 0L

    def receive = {
      case Run ⇒
        // first some warmup
        sendBatch()
        // then Start, which will echo back here
        target ! Start

      case Start ⇒
        println(s"${self.path.name}: Starting benchmark of $totalMessages messages with burst size $burstSize and payload size $payloadSize")
        startTime = System.nanoTime
        remaining = totalMessages
        // have a few batches in flight to make sure there are always messages to send
        (1 to 3).foreach { _ ⇒
          val t0 = System.nanoTime()
          sendBatch()
          sendFlowControl(t0)
        }

      case c @ FlowControl(t0) ⇒
        val now = System.nanoTime()
        val duration = NANOSECONDS.toMillis(now - t0)
        maxRoundTripMillis = math.max(maxRoundTripMillis, duration)

        sendBatch()
        sendFlowControl(startTime)

      case EndResult(totalReceived) ⇒
        val took = NANOSECONDS.toMillis(System.nanoTime - startTime)
        val throughtput = (totalReceived * 1000.0 / took).toInt
        println(
          s"== ${self.path.name}: It took $took ms to deliver $totalReceived messages, " +
            s"dropped ${totalMessages - totalReceived}, " +
            s"throughtput $throughtput msg/s, " +
            s"max round-trip $maxRoundTripMillis ms, burst size $burstSize, " +
            s"payload size $payloadSize")
        context.stop(self)
    }

    def sendBatch(): Unit = {
      val batchSize = math.min(remaining, burstSize)
      var i = 0
      while (i < batchSize) {
        target ! payload
        i += 1
      }
      remaining -= batchSize
    }

    def sendFlowControl(t0: Long): Unit = {
      if (remaining <= 0)
        target ! End
      else
        target ! FlowControl(t0)
    }
  }

  final case class TestSettings(
    testName: String,
    totalMessages: Long,
    burstSize: Int,
    payloadSize: Int)

}

class MaxThroughputSpecMultiJvmNode1 extends MaxThroughputSpec
class MaxThroughputSpecMultiJvmNode2 extends MaxThroughputSpec

abstract class MaxThroughputSpec
  extends MultiNodeSpec(MaxThroughputSpec)
  with STMultiNodeSpec with ImplicitSender {

  import MaxThroughputSpec._

  val totalMessagesFactor = system.settings.config.getDouble("MaxThroughputSpec.totalMessagesFactor")

  def adjustedTotalMessages(n: Long): Long = (n * totalMessagesFactor).toLong

  override def initialParticipants = roles.size

  def remoteSettings = system.asInstanceOf[ExtendedActorSystem].provider.asInstanceOf[RemoteActorRefProvider].remoteSettings

  def address(roleName: RoleName): Address = {
    println(s"# $roleName -> ${node(roleName).address}") // FIXME
    node(roleName).address
  }

  lazy val reporterExecutor = Executors.newFixedThreadPool(1)
  def reporter(name: String): RateReporter = {
    val r = new RateReporter(SECONDS.toNanos(1), new RateReporter.Reporter {
      override def onReport(messagesPerSec: Double, bytesPerSec: Double, totalMessages: Long, totalBytes: Long): Unit = {
        println(name + ": %.03g msgs/sec, %.03g bytes/sec, totals %d messages %d MB".format(
          messagesPerSec, bytesPerSec, totalMessages, totalBytes / (1024 * 1024)))
      }
    })
    reporterExecutor.execute(r)
    r
  }

  override def afterAll(): Unit = {
    reporterExecutor.shutdown()
    super.afterAll()
  }

  def identifyReceiver(name: String, r: RoleName = second): ActorRef = {
    system.actorSelection(RootActorPath(address(r)) / "user" / name) ! Identify(None)
    expectMsgType[ActorIdentity].ref.get
  }

  def test(testSettings: TestSettings): Unit = {
    import testSettings._
    val receiverName = testName + "-rcv"

    runOn(second) {
      val rep = reporter(testName)
      val receiver = system.actorOf(receiverProps(rep, payloadSize), receiverName)
      enterBarrier(receiverName + "-started")
      enterBarrier(testName + "-done")
      rep.halt()
    }

    runOn(first) {
      enterBarrier(receiverName + "-started")
      val receiver = identifyReceiver(receiverName)
      val snd = system.actorOf(senderProps(receiver, testSettings), testName + "-snd")
      watch(snd)
      snd ! Run
      expectTerminated(snd, 60.seconds * totalMessagesFactor)
      enterBarrier(testName + "-done")
    }

    enterBarrier("after-" + testName)
  }

  "Max throughput of Artery" must {

    val scenarios = List(
      TestSettings(
        testName = "1-to-1",
        totalMessages = adjustedTotalMessages(1000000),
        burstSize = 1000,
        payloadSize = 100),
      TestSettings(
        testName = "1-to-1-large-burst",
        totalMessages = adjustedTotalMessages(1000000),
        burstSize = 10000, // FIXME strange, we get much better throughput with 10000, why can't we exhaust the Source.queue?
        payloadSize = 100),
      TestSettings(
        testName = "1-to-1-size-1k",
        totalMessages = adjustedTotalMessages(100000),
        burstSize = 1000,
        payloadSize = 1000),
      TestSettings(
        testName = "1-to-1-size-10k",
        totalMessages = adjustedTotalMessages(10000),
        burstSize = 1000,
        payloadSize = 10000))

    for (s ← scenarios) {
      s"be great for ${s.testName}, burstSize = ${s.burstSize}, payloadSize = ${s.payloadSize}" in test(s)
    }

    // TODO add more tests, such as 5-to-5 sender receiver pairs

  }
}
