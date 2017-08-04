package esl

import akka.stream.scaladsl.{Flow, Sink}
import esl.domain.{CommandReply, FSMessage}

/**
  * FSSocket represent fs inbound/outbound socket connection and connection reply
  *
  * @param fsConnection type of FSConnection connection
  * @param commandReply : CommandReply reply of first from fs
  * @tparam FS type of fs socket connection, it could be Inbound/Outbound
  */
case class FSSocket[FS <: FSConnection](fsConnection: FS, commandReply: CommandReply) extends InfantFSSocket[FS]

trait InfantFSSocket[FS <: FSConnection] {

  protected val fsConnection: FS
  protected val commandReply: CommandReply

  def attachSink(anotherSink: Sink[(FS, List[FSMessage]), _]): FSSocket[FS] = {
    val flow = Flow[List[FSMessage]].map(f => fsConnection -> f)
      .to(anotherSink)
    fsConnection.attachSink(flow)
    FSSocket(fsConnection, commandReply)
  }
}

object InfantFSSocket {
  def apply[FS <: FSConnection](fsConn: FS, cmdReply: CommandReply): InfantFSSocket[FS] = new InfantFSSocket[FS] {
    override protected val fsConnection: FS = fsConn
    override protected val commandReply: CommandReply = cmdReply
  }
}
