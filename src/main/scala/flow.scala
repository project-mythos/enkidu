package Enkidu

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import io.netty.channel

import com.twitter.util.{Future, Promise, Return, Time, Try}
import com.twitter.concurrent.AsyncQueue
import channel.{ChannelException, EventLoopGroup}

import java.net.SocketAddress
import scala.util.control.{NonFatal, NoStackTrace}







class ChannelClosedException(addr: SocketAddress) extends Throwable

trait Flow[In, Out] {

  def read(): Future[Out]
  def write(in: In): Future[Unit]
  def close(): Future[Unit]

  def remoteAddress: SocketAddress
  def localAddress: SocketAddress
  def closed(): Boolean
}




object Flow {


  def cast[In1, Out1](trans: Flow[Any, Any]) = {
    trans.asInstanceOf[Flow[In1, Out1]]
  }
 
}



class ChannelFlow(
  chan: channel.Channel,
  readQueue: AsyncQueue[Any] = new AsyncQueue[Any],
  omitStackTraceOnInactive: Boolean = false
) extends Flow[Any, Any] {

  val msgsNeeded = new AtomicInteger(0)
  val alreadyClosed = new AtomicBoolean(false)




  def readIfNeeded(): Unit = {
    if (!chan.config.isAutoRead) {
      if (msgsNeeded.get >= 0) chan.read()
    }
  }

  def incrementAndReadIfNeeded(): Unit = {
    val value = msgsNeeded.incrementAndGet()
    if (value >= 0 && !chan.config.isAutoRead) {
      chan.read()
    }
  }

  def decrement() = {
    msgsNeeded.decrementAndGet()
  }


  def close() = {
    val p = Promise[Unit]()
    alreadyClosed.set(true)

    if (chan.isOpen) {
      FutureConversion.toFuture( chan.close() ){ x => p.become(Future.Done) }
    } else Future.Done 
  }

  def closed() = alreadyClosed.get()

  def fail(exc: Throwable) = {
    readQueue.fail(exc, discard = false)
    close()
  }



  def localAddress = chan.localAddress()

  def remoteAddress = chan.remoteAddress()

  def read(): Future[Any] = {
    val p = Promise[Any]()
    incrementAndReadIfNeeded()
    p.become(readQueue.poll())
    p
  }


  def write(msg: Any): Future[Unit] = {
    val op = chan.writeAndFlush(msg)

    val p = Promise[Unit]()

    op.addListener(new channel.ChannelFutureListener {
      def operationComplete(f: channel.ChannelFuture): Unit =


      if (f.isSuccess) p.setDone()

      else {
        p.setException(new ChannelException(f.cause) )
      }
    })

    p
  }




  chan.pipeline.addLast(
    "NettyTransporter",
    new channel.ChannelInboundHandlerAdapter {

      override def channelActive(ctx: channel.ChannelHandlerContext): Unit = {
        // Upon startup we immediately begin the process of buffering at most one inbound
        // message in order to detect channel close events. Otherwise we would have
        // different buffering behavior before and after the first `Transport.read()` event.
        readIfNeeded()
        super.channelActive(ctx)
      }

      override def channelReadComplete(ctx: channel.ChannelHandlerContext): Unit = {
        // Check to see if we need more data
        readIfNeeded()
        super.channelReadComplete(ctx)
      }

      override def channelRead(ctx: channel.ChannelHandlerContext, msg: Any): Unit = {
        decrement()

        if (!readQueue.offer(msg)) // Dropped messages are fatal
          fail(new Throwable(s"offer failure on $this $readQueue"))
      }

      override def channelInactive(ctx: channel.ChannelHandlerContext): Unit = {
        alreadyClosed.set(true)
        if (omitStackTraceOnInactive) {
          fail(new ChannelClosedException(remoteAddress) with NoStackTrace)
        } else fail(new ChannelClosedException(remoteAddress))
      }

      override def exceptionCaught(ctx: channel.ChannelHandlerContext, e: Throwable): Unit = {
        fail(new ChannelException(e) )
      }
    }
  )

  
}


class QueueFlow[In, Out](writeq: AsyncQueue[In] = new AsyncQueue[In], readq: AsyncQueue[Out] = new AsyncQueue[Out])
    extends Flow[In, Out] {

  val alreadyClosed = new AtomicBoolean() 

  def write(input: In): Future[Unit] = {
    writeq.offer(input)
    Future.Done
  }

  def read(): Future[Out] =
    readq.poll()


  def close(): Future[Unit] = {
    alreadyClosed.compareAndSet(false, true)
    Future.Done
  }

  def closed(): Boolean = {
    alreadyClosed.get()
  }
  def localAddress: SocketAddress = new SocketAddress {}
  def remoteAddress: SocketAddress = new SocketAddress {}

}
