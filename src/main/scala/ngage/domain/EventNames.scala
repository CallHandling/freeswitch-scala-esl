/*
 * Copyright 2017 Call Handling Services Ltd.
 * <http://www.callhandling.co.uk>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ngage.domain

object EventNames {

  sealed trait EventName {
    val name: String
  }

  case object NewChannel extends EventName {
    override val name: String = "NEW_CHANNEL"
  }

  case object Custom extends EventName {
    override val name: String = "CUSTOM"
  }

  case object Clone extends EventName {
    override val name: String = "CLONE"
  }

  case object ChannelCreate extends EventName {
    override val name: String = "CHANNEL_CREATE"
  }

  case object ChannelDestroy extends EventName {
    override val name: String = "CHANNEL_DESTROY"
  }

  case object ChannelState extends EventName {
    override val name: String = "CHANNEL_STATE"
  }

  case object ChannelCallState extends EventName {
    override val name: String = "CHANNEL_CALL_STATE"
  }

  case object ChannelAnswer extends EventName {
    override val name: String = "CHANNEL_ANSWER"
  }

  case object ChannelHangup extends EventName {
    override val name: String = "CHANNEL_HANGUP"
  }

  case object ChannelHangupComplete extends EventName {
    override val name: String = "CHANNEL_HANGUP_COMPLETE"
  }

  case object ChannelExecute extends EventName {
    override val name: String = "CHANNEL_EXECUTE"
  }

  case object ChannelExecuteComplete extends EventName {
    override val name: String = "CHANNEL_EXECUTE_COMPLETE"
  }

  case object ChannelHold extends EventName {
    override val name: String = "CHANNEL_HOLD"
  }

  case object ChannelUnhold extends EventName {
    override val name: String = "CHANNEL_UNHOLD"
  }

  case object ChannelBridge extends EventName {
    override val name: String = "CHANNEL_BRIDGE"
  }

  case object ChannelUnbridge extends EventName {
    override val name: String = "CHANNEL_UNBRIDGE"
  }

  case object ChannelProgress extends EventName {
    override val name: String = "CHANNEL_PROGRESS"
  }

  case object ChannelProgressMedia extends EventName {
    override val name: String = "CHANNEL_PROGRESS_MEDIA"
  }

  case object ChannelOutgoing extends EventName {
    override val name: String = "CHANNEL_OUTGOING"
  }

  case object ChannelPark extends EventName {
    override val name: String = "CHANNEL_PARK"
  }

  case object ChannelUnpark extends EventName {
    override val name: String = "CHANNEL_UNPARK"
  }

  case object ChannelApplication extends EventName {
    override val name: String = "CHANNEL_APPLICATION"
  }

  case object ChannelOriginate extends EventName {
    override val name: String = "CHANNEL_ORIGINATE"
  }

  case object ChannelUuid extends EventName {
    override val name: String = "CHANNEL_UUID"
  }

  case object Api extends EventName {
    override val name: String = "API"
  }

  case object Log extends EventName {
    override val name: String = "LOG"
  }

  case object InboundChan extends EventName {
    override val name: String = "INBOUND_CHAN"
  }

  case object OutboundChan extends EventName {
    override val name: String = "OUTBOUND_CHAN"
  }

  case object Startup extends EventName {
    override val name: String = "STARTUP"
  }

  case object Shutdown extends EventName {
    override val name: String = "SHUTDOWN"
  }

  case object Publish extends EventName {
    override val name: String = "PUBLISH"
  }

  case object Unpublish extends EventName {
    override val name: String = "UNPUBLISH"
  }

  case object Talk extends EventName {
    override val name: String = "TALK"
  }

  case object Notalk extends EventName {
    override val name: String = "NOTALK"
  }

  case object SessionCrash extends EventName {
    override val name: String = "SESSION_CRASH"
  }

  case object ModuleLoad extends EventName {
    override val name: String = "MODULE_LOAD"
  }

  case object Dtmf extends EventName {
    override val name: String = "DTMF"
  }

  case object Message extends EventName {
    override val name: String = "MESSAGE"
  }

  case object PresenceIn extends EventName {
    override val name: String = "PRESENCE_IN"
  }

  case object NotifyIn extends EventName {
    override val name: String = "NOTIFY_IN"
  }

  case object PresenceOut extends EventName {
    override val name: String = "PRESENCE_OUT"
  }

  case object PresenceProbe extends EventName {
    override val name: String = "PRESENCE_PROBE"
  }

  case object MessageWaiting extends EventName {
    override val name: String = "MESSAGE_WAITING"
  }

  case object MessageQuery extends EventName {
    override val name: String = "MESSAGE_QUERY"
  }

  case object Roster extends EventName {
    override val name: String = "ROSTER"
  }

  case object Codec extends EventName {
    override val name: String = "CODEC"
  }

  case object BackgroundJob extends EventName {
    override val name: String = "BACKGROUND_JOB"
  }

  case object DetectedSpeech extends EventName {
    override val name: String = "DETECTED_SPEECH"
  }

  case object DetectedTone extends EventName {
    override val name: String = "DETECTED_TONE"
  }

  case object PrivateCommand extends EventName {
    override val name: String = "PRIVATE_COMMAND"
  }

  case object Heartbeat extends EventName {
    override val name: String = "HEARTBEAT"
  }

  case object Trap extends EventName {
    override val name: String = "TRAP"
  }

  case object AddSchedule extends EventName {
    override val name: String = "ADD_SCHEDULE"
  }

  case object DelSchedule extends EventName {
    override val name: String = "DEL_SCHEDULE"
  }

  case object ExeSchedule extends EventName {
    override val name: String = "EXE_SCHEDULE"
  }

  case object ReSchedule extends EventName {
    override val name: String = "RE_SCHEDULE"
  }

  case object Reloadxml extends EventName {
    override val name: String = "RELOADXML"
  }

  case object Notifyer extends EventName {
    override val name: String = "NOTIFYER"
  }

  case object SendMessage extends EventName {
    override val name: String = "SEND_MESSAGE"
  }

  case object RecvMessage extends EventName {
    override val name: String = "RECV_MESSAGE"
  }

  case object RequestParams extends EventName {
    override val name: String = "REQUEST_PARAMS"
  }

  case object ChannelData extends EventName {
    override val name: String = "CHANNEL_DATA"
  }

  case object General extends EventName {
    override val name: String = "GENERAL"
  }

  case object Command extends EventName {
    override val name: String = "COMMAND"
  }

  case object SessionHeartbeat extends EventName {
    override val name: String = "SESSION_HEARTBEAT"
  }

  case object ClientDisconnected extends EventName {
    override val name: String = "CLIENT_DISCONNECTED"
  }

  case object ServerDisconnected extends EventName {
    override val name: String = "SERVER_DISCONNECTED"
  }

  case object SendInfo extends EventName {
    override val name: String = "SEND_INFO"
  }

  case object RecvInfo extends EventName {
    override val name: String = "RECV_INFO"
  }

  case object RecvRtcpMessage extends EventName {
    override val name: String = "RECV_RTCP_MESSAGE"
  }

  case object CallSecure extends EventName {
    override val name: String = "CALL_SECURE"
  }

  case object Nat extends EventName {
    override val name: String = "NAT"
  }

  case object RecordStart extends EventName {
    override val name: String = "RECORD_START"
  }

  case object RecordStop extends EventName {
    override val name: String = "RECORD_STOP"
  }

  case object PlaybackStart extends EventName {
    override val name: String = "PLAYBACK_START"
  }

  case object PlaybackStop extends EventName {
    override val name: String = "PLAYBACK_STOP"
  }

  case object CallUpdate extends EventName {
    override val name: String = "CALL_UPDATE"
  }

  case object Failure extends EventName {
    override val name: String = "FAILURE"
  }

  case object SocketData extends EventName {
    override val name: String = "SOCKET_DATA"
  }

  case object MediaBugStart extends EventName {
    override val name: String = "MEDIA_BUG_START"
  }

  case object MediaBugStop extends EventName {
    override val name: String = "MEDIA_BUG_STOP"
  }

  case object ConferenceDataQuery extends EventName {
    override val name: String = "CONFERENCE_DATA_QUERY"
  }

  case object ConferenceData extends EventName {
    override val name: String = "CONFERENCE_DATA"
  }

  case object CallSetupReq extends EventName {
    override val name: String = "CALL_SETUP_REQ"
  }

  case object CallSetupResult extends EventName {
    override val name: String = "CALL_SETUP_RESULT"
  }

  case object All extends EventName {
    override val name: String = "ALL"
  }

  val events: Map[String, EventName] = List(
    NewChannel, Custom, Clone, ChannelCreate, ChannelDestroy, ChannelState, ChannelCallState, ChannelAnswer,
    ChannelHangup, ChannelHangupComplete, ChannelExecute, ChannelExecuteComplete, ChannelHold, ChannelUnhold,
    ChannelBridge, ChannelUnbridge, ChannelProgress, ChannelProgressMedia, ChannelOutgoing, ChannelPark, ChannelUnpark,
    ChannelApplication, ChannelOriginate, ChannelUuid, Api, Log, InboundChan, OutboundChan, Startup, Shutdown, Publish,
    Unpublish, Talk, Notalk, SessionCrash, ModuleLoad, Dtmf, Message, PresenceIn, NotifyIn, PresenceOut, PresenceProbe,
    MessageWaiting, MessageQuery, Roster, Codec, BackgroundJob, DetectedSpeech, DetectedTone, PrivateCommand, Heartbeat,
    Trap, AddSchedule, DelSchedule, ExeSchedule, ReSchedule, Reloadxml, Notifyer, SendMessage, RecvMessage, RequestParams,
    ChannelData, General, Command, SessionHeartbeat, ClientDisconnected, ServerDisconnected, SendInfo, RecvInfo,
    RecvRtcpMessage, CallSecure, Nat, RecordStart, RecordStop, PlaybackStart, PlaybackStop, CallUpdate,
    Failure, SocketData, MediaBugStart, MediaBugStop, ConferenceDataQuery, ConferenceData, CallSetupReq,
    CallSetupResult, All).map(event => event.name -> event).toMap
}

