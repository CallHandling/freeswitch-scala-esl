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

package esl.domain

import akka.stream.QueueOfferResult
import esl.domain.EventNames.EventName

import scala.concurrent.Future
import scala.concurrent.duration.{Duration, DurationInt, FiniteDuration}
import CallCommands.{LINE_TERMINATOR, MESSAGE_TERMINATOR}
import esl.domain.HangupCauses.HangupCause

import scala.language.postfixOps

sealed trait FSCommand {
  val eventUuid: String = java.util.UUID.randomUUID.toString
}

sealed trait FSExecuteApp extends FSCommand {
  val config: ApplicationCommandConfig
  val application: String
  def args: String = ""

  override def toString: String = {
    val b = StringBuilder.newBuilder
    b.append(
      s"sendmsg ${config.channelUuid}${LINE_TERMINATOR}Event-UUID: $eventUuid$LINE_TERMINATOR"
    )
    b.append(
      s"call-command: execute${LINE_TERMINATOR}execute-app-name: $application$LINE_TERMINATOR"
    )
    if (config.eventLock)
      b.append(s"event-lock: ${config.eventLock}$LINE_TERMINATOR")
    if (config.loops > 1) b.append(s"loops: ${config.loops}$LINE_TERMINATOR")
    if (config.async) b.append(s"async: ${config.async}$LINE_TERMINATOR")
    if (args.length > 0)
      b.append(
        s"content-type: text/plain${LINE_TERMINATOR}content-length: ${args.length}$MESSAGE_TERMINATOR$args$LINE_TERMINATOR"
      )
    b.toString()
  }
}

/**
  *
  * @param channelUuid The uuid of the channel to execute the application command on,
  *                    not required in outbound mode if you want to effect the outbound channel
  * @param eventLock   Force application to complete before next command is parsed
  * @param loops       Number of times to invoke the command, default 1
  * @param async       Set the execution mode to async, has no effect in Outbound async mode
  */
final case class ApplicationCommandConfig(
    channelUuid: String = "",
    eventLock: Boolean = false,
    loops: Int = 1,
    async: Boolean = false
) {
  def withChannelId(channelId: String): ApplicationCommandConfig =
    copy(channelUuid = channelId)
}

object CallCommands {

  private implicit class CommandVarsSyntax(private val vars: Seq[String]) {
    def asVarsString: String = vars.mkString("{", ",", "}")
  }

  val MESSAGE_TERMINATOR = "\n\n"
  val LINE_TERMINATOR = "\n"

  final case class None(config: ApplicationCommandConfig) extends FSExecuteApp {
    override val application: String = "none"
  }

  /**
    * Hangs up a channel, with an optional cause code supplied.
    * <action application="hangup" data="USER_BUSY"/>
    *
    * @param cause  : HangupCause
    * @param config : ApplicationCommandConfig
    */
  final case class Hangup(
      cause: Option[HangupCause],
      config: ApplicationCommandConfig
  ) extends FSExecuteApp {
    override val application: String =
      cause.fold(s"hangup$MESSAGE_TERMINATOR")(_ => "hangup")
    override val args: String = cause.fold("")(_.name)
  }

  final case class Break(config: ApplicationCommandConfig, app: Option[String])
      extends FSExecuteApp {
    override val application: String = s"break$MESSAGE_TERMINATOR"
    override lazy val args: String = app.getOrElse("")
  }

  /**
    * Plays a sound file on the current channel.
    *
    * @param filePath : String file path that you want to play
    * @param config   : ApplicationCommandConfig
    */
  final case class PlayFile(filePath: String, config: ApplicationCommandConfig)
      extends FSExecuteApp {
    override val application: String = "playback"
    override val args: String = filePath
  }

  /**
    * Immediately transfer the calling channel to a new context. If there happens to be an xml extension named <destination_number>
    * then control is "warped" directly to that extension. Otherwise it goes through the entire context checking for a match.
    *
    * @param extension : String extension name
    * @param config    : ApplicationCommandConfig
    */
  final case class TransferTo(
      extension: String,
      config: ApplicationCommandConfig
  ) extends FSExecuteApp {
    override val application: String = "transfer"
    override val args: String = extension
  }

  /**
    * Answer the call for a channel.This sets up duplex audio between the calling ''A'' leg and the FreeSwitch server.
    * It is not about other endpoints. The server might need to 'answer' a call to play an audio file or to receive DTMF from the call.
    * Once answered, calls can still be bridged to other extensions. Because a bridge after an answer is actually a transfer,
    * the ringback tones sent to the caller will be defined by transfer_ringback.
    *
    * @param config : ApplicationCommandConfig
    */
  final case class Answer(config: ApplicationCommandConfig)
      extends FSExecuteApp {
    override val application: String = s"answer$MESSAGE_TERMINATOR"
  }

  final case class AuthCommand(password: String) extends FSCommand {
    override def toString: String = s"auth $password$MESSAGE_TERMINATOR"
  }

  /**
    * Set a channel variable for the channel calling the application.
    * Usage set <channel_variable>=<value>
    *
    * @param varName  : String variable name
    * @param varValue : String variable value
    * @param config   : ApplicationCommandConfig
    * @return Future[CommandReply]
    */
  final case class SetVar(
      varName: String,
      varValue: String,
      config: ApplicationCommandConfig
  ) extends FSExecuteApp {
    override val application: String = "set"
    override val args: String = s"$varName=$varValue"
  }

  /**
    * att_xfer <channel_url>
    * Bridge a third party specified by channel_url onto the call, speak privately, then bridge original caller to target channel_url of att_xfer.
    *
    * @param destination   : String target channel_url of att_xfer
    * @param conferenceKey : Char "attxfer_conf_key" - can be used to initiate a three way transfer (deafault '0')
    * @param hangupKey     : Char "attxfer_hangup_key" - can be used to hangup the call after user wants to end his or her call (deafault '*')
    * @param cancelKey     : Char "attxfer_cancel_key" - can be used to cancel a tranfer just like origination_cancel_key, but straight from the att_xfer code (deafault '#')
    * @param config        : ApplicationCommandConfig
    */
  final case class AttXfer(
      destination: String,
      conferenceKey: Char,
      hangupKey: Char,
      cancelKey: Char,
      config: ApplicationCommandConfig
  ) extends FSExecuteApp {
    override val application: String = "att_xfer"
    override val args: String =
      s"{attxfer_conf_key=$conferenceKey,attxfer_hangup_key=$hangupKey,attxfer_cancel_key=$cancelKey}$destination"
  }

  /**
    * To dial multiple contacts all at once:
    * <action application="bridge" data="sofia/internal/1010@sip.yourprovider.com,sofia/sip/1011@sip.yourprovider.com"/>
    * To dial multiple contacts one at a time:
    * <action application="bridge" data="sofia/internal/1010@sip.yourprovider.com|sofia/sip/1011@sip.yourprovider.com"/>
    *
    * @param targets  :List[String] list of an external SIP address or termination provider
    * @param dialType : DialType To dial multiple contacts all at once then separate targets by comma(,) or To dial multiple contacts one at a time
    *                 then separate targets by pipe(|)
    * @param config   :ApplicationCommandConfig
    */
  final case class Bridge(
      targets: List[String],
      dialType: DialType,
      config: ApplicationCommandConfig
  ) extends FSExecuteApp {
    override val application: String = "bridge"
    override val args: String = targets.mkString(dialType.separator)
  }

  final case class AddToConference(
      conferenceId: String,
      profile: String,
      config: ApplicationCommandConfig,
      flags: Seq[String] = Seq.empty
  ) extends FSExecuteApp {
    override val application: String = "conference"
    override val args: String =
      s"$conferenceId@$profile${flags.mkString("+flags{", "|", "}")}"
  }

  final case class LeaveConference(
      conferenceId: String,
      config: ApplicationCommandConfig
  ) extends FSExecuteApp {
    override val application: String = "conference"
    override val args: String = s"hangup:$conferenceId"
  }

  final case class ConferenceCommand(
      conferenceId: String,
      command: ConferenceCommand.ConferenceCommandType,
      commandConfig: ApplicationCommandConfig
  ) extends FSExecuteApp {

    lazy val config: ApplicationCommandConfig =
      commandConfig.copy(channelUuid = "")

    override val application: String = "conference"

    override val args: String = s"$conferenceId $command"

  }

  object ConferenceCommand {

    sealed trait ConferenceCommandType {
      def forConference(conferenceId:String, config: ApplicationCommandConfig) : ConferenceCommand
    }
    final case class Play(
        file: String,
        nomux: Boolean = false,
        memberId: Option[String] = Option.empty,
        async: Boolean = true
    ) extends ConferenceCommandType {

      override def toString: String =
        s"play $file${(memberId, async, nomux) match {
          case (Some(memberId), _, true) => s" $memberId nomux"
          case (Some(memberId), _, _)    => s" $memberId"
          case (_, true, true)           => " async nomux"
          case (_, true, _)              => " async nomux"
          case _                         => ""
        }}"

      override def forConference(conferenceId: String, config: ApplicationCommandConfig): ConferenceCommand = {
        ConferenceCommand(conferenceId, this, config)
      }
    }

    final case class PausePlay() extends ConferenceCommandType {

      override def toString: String = "pause_play"

      override def forConference(conferenceId: String, config: ApplicationCommandConfig): ConferenceCommand = {
        ConferenceCommand(conferenceId, this, config)
      }
    }
  }

/*
  final case class Dial(
                         target: String,
                         uniqueId: String,
                         numberPresentation: Dial.NumberPresentation,
                         config: ApplicationCommandConfig,
                         timeout: FiniteDuration,
                         retries: Option[Dial.Retry] = Option.empty
                       ) extends FSCommand {
    override def toString: String = {
      val vars = Seq(
        "ignore_early_media=true",
        s"originate_timeout=${timeout.toSeconds}",
        s"origination_uuid=$uniqueId"
      ) ++
        numberPresentation.asOriginateArgs ++
        retries.map(_.asOriginateArgs).getOrElse(Nil)

      s"""bgapi originate ${vars.asVarsString}$target &park()
         |Job-UUID: $eventUuid""".stripMargin
    }
  }*/

  final case class Dial(
      target: String,
      uniqueId: String,
      numberPresentation: Dial.NumberPresentation,
      config: ApplicationCommandConfig,
      timeout: FiniteDuration,
      retries: Option[Dial.Retry] = Option.empty
  ) extends FSExecuteApp {

    override val application: String = "set"
    override lazy val args: String = {
      val vars = Seq(
        "ignore_early_media=true",
        s"originate_timeout=${timeout.toSeconds}",
        s"origination_uuid=$uniqueId"
      ) ++
        numberPresentation.asOriginateArgs ++
        retries.map(_.asOriginateArgs).getOrElse(Nil)

      s"""dial_$eventUuid=$${
         |bgapi originate {${vars.asVarsString}}$target &park()
         |Job-UUID: $eventUuid
         |}""".stripMargin
    }
  }

  object Dial {

    case class Retry(retries: Int = 1, sleep: FiniteDuration = 5 seconds) {
      def asOriginateArgs: Seq[String] = {
        Seq(
          s"originate_retries=$retries",
          s"originate_retry_sleep_ms=${sleep.toMillis}"
        )
      }
    }
    case class NumberPresentation(number: String, name: String) {
      def asOriginateArgs: Seq[String] = {
        Seq(
          s"origination_caller_id_number=$number",
          s"origination_caller_id_name=$name"
        )
      }
    }

    object NumberPresentation {
      lazy val hidden: NumberPresentation =
        NumberPresentation("anonymous", "anonymous")
    }
  }

  final case object ConnectCommand extends FSCommand {
    override def toString: String = s"connect$MESSAGE_TERMINATOR"
  }

  final case object LingerCommand extends FSCommand {
    override def toString: String = s"linger$MESSAGE_TERMINATOR"
  }

  /**
    * Freeswitch command as raw command that present as string
    *
    * @param command : String
    */
  final case class CommandAsString(
      command: String,
      override val eventUuid: String
  ) extends FSCommand {
    override def toString: String = command
  }

  /**
    * The event command are used to subscribe on events from FreeSWITCH
    * event plain ALL
    * event plain CHANNEL_CREATE CHANNEL_DESTROY CUSTOM conference::maintenance sofia::register sofia::expire
    * event xml ALL
    * event json CHANNEL_ANSWER
    *
    * @param events : List[EventName]
    */
  final case class SubscribeEvents(events: List[EventName]) extends FSCommand {
    override def toString: String =
      s"event plain ${events.map(_.name).mkString(" ")}$MESSAGE_TERMINATOR"
  }

  /**
    * The 'myevents' subscription allows your inbound socket connection to behave like an outbound socket connect.
    * It will "lock on" to the events for a particular uuid and will ignore all other events
    * myevents plain <uuid>
    * myevents json <uuid>
    * myevents xml <uuid>
    * Once the socket connection has locked on to the events for this particular uuid it will NEVER see any events
    * that are not related to the channel, even if subsequent event commands are sent
    *
    * @param uuid : String
    */
  final case class SubscribeMyEvents(uuid: String) extends FSCommand {
    override def toString: String = s"myevents plain $uuid$MESSAGE_TERMINATOR"
  }

  final case class CreateUUID(config: ApplicationCommandConfig)
    extends FSCommand {
    override def toString: String =s"bgapi create_uuid${LINE_TERMINATOR}Job-UUID: $eventUuid$MESSAGE_TERMINATOR"
  }

  /**
    * Specify event types to listen for. Note, this is not a filter out but rather a "filter in," that is,
    * when a filter is applied only the filtered values are received. Multiple filters on a socket connection are allowed.
    * Usage:
    * filter <EventHeader> <ValueToFilter>
    *
    * @param events : Map[EventName, String] mapping of events and their value
    * @param config : ApplicationCommandConfig
    */
  final case class Filter(
      events: Map[EventName, String],
      config: ApplicationCommandConfig
  ) extends FSCommand {
    override def toString: String =
      s"filter ${events.map { case (key, value) => s"$value ${key.name}" }.mkString(" ")}$MESSAGE_TERMINATOR"
  }

  /**
    * Specify events of specific channel UUId to listen for. Note, this is not a filter out but rather a "filter in," that is,
    * when a filter is applied only the filtered values are received. Multiple filters on a socket connection are allowed.
    * Usage:
    * filter <Unique-ID> <uuid>
    *
    * @param uuid : Channel uuid to filter in
    * @param config : ApplicationCommandConfig
    */
  final case class FilterUUId(uuid: String, config: ApplicationCommandConfig)
      extends FSCommand {
    override def toString: String =
      s"filter Unique-ID ${uuid}$MESSAGE_TERMINATOR"
  }

  final case class FilterX(filter: String, config: ApplicationCommandConfig)
    extends FSCommand {
    override def toString: String =
      s"filter ${filter}$MESSAGE_TERMINATOR"
  }

  /**
    * filter delete
    * Specify the events which you want to revoke the filter. filter delete can be used when some filters are applied wrongly or
    * when there is no use of the filter.
    * Usage:
    * filter delete <EventHeader> <ValueToFilter>
    *
    * @param events :Map[EventName, String] mapping of events and their value
    * @param config :ApplicationCommandConfig
    */
  final case class DeleteFilter(
      events: Map[EventName, String],
      config: ApplicationCommandConfig
  ) extends FSCommand {
    override def toString: String =
      s"filter delete ${events.map { case (key, value) => s"$value ${key.name}" }.mkString(" ")}$MESSAGE_TERMINATOR"
  }

  /**
    * filterUUId delete
    * Specify channel UUId to revoke the filter. filter delete can be used when some filters are applied wrongly or
    * when there is no use of the filter.
    * Usage:
    * filter delete <Unique-ID> <uuid>
    *
    * @param uuid : Channel uuid to filter out
    * @param config :ApplicationCommandConfig
    */
  final case class DeleteUUIdFilter(
      uuid: String,
      config: ApplicationCommandConfig
  ) extends FSCommand {
    override def toString: String =
      s"filter delete Unique-ID ${uuid}$MESSAGE_TERMINATOR"
  }

  /**
    * Allows one channel to bridge itself to the a or b leg of another call. The remaining leg of the original call gets hungup
    * Usage: intercept [-bleg] <uuid>
    *
    * @param uuid   : String
    * @param config :ApplicationCommandConfig
    */
  final case class Intercept(uuid: String, config: ApplicationCommandConfig)
      extends FSExecuteApp {
    override val application: String = "intercept"
    override val args: String = uuid
  }

  /**
    * Read DTMF (touch-tone) digits.
    * Usage
    * read <min> <max> <sound file> <variable name> <timeout> <terminators>
    * min = Minimum number of digits to fetch.
    * max = Maximum number of digits to fetch.
    * sound file = Sound file to play before digits are fetched.
    * variable name = Channel variable that digits should be placed in.
    * timeout = Number of milliseconds to wait on each digit
    * terminators = Digits used to end input if less than <min> digits have been pressed. (Typically '#')
    *
    * @param params : ReadParameters
    * @param config : ApplicationCommandConfig
    */
  final case class Read(
      params: ReadParameters,
      config: ApplicationCommandConfig
  ) extends FSExecuteApp {
    override val application: String = "read"
    override val args: String = List(
      params.min.toString,
      params.max.toString,
      params.soundFile,
      params.variableName,
      params.timeout.toMillis.toString,
      params.terminators.map(_.toString).mkString(",")
    ).mkString(" ")
  }

  /**
    * Speak a phrase of text using a predefined phrase macro
    *
    * @param variableName : String variable name
    * @param config       : ApplicationCommandConfig
    */
  final case class Phrase(
      variableName: String,
      config: ApplicationCommandConfig
  ) extends FSExecuteApp {
    override val application: String = "phrase"
    override val args: String = "spell,${" + variableName + "}"
  }

  /**
    * Pause the channel for a given number of milliseconds, consuming the audio for that period of time.
    * Calling sleep also will consume any outstanding RTP on the operating system's input queue,
    * which can be very useful in situations where audio becomes backlogged.
    * To consume DTMFs, use the sleep_eat_digits variable.
    * Usage: <action application="sleep" data=<milliseconds>/>
    */
  final case class Sleep(
      numberOfMillis: Duration,
      config: ApplicationCommandConfig
  ) extends FSExecuteApp {
    override val application: String = "sleep"
    override val args: String = numberOfMillis.toMillis.toString
  }

  case class Hold(config: ApplicationCommandConfig) extends FSExecuteApp {
    override val application: String = "hold"
    override val args: String = ""
  }
  case class OffHold(config: ApplicationCommandConfig) extends FSExecuteApp {
    override val application: String = "unhold"
    override val args: String = ""
  }

  /**
    * pre_answer establishes media (early media) but does not answer.
    *
    * @param config : ApplicationCommandConfig
    */
  final case class PreAnswer(config: ApplicationCommandConfig)
      extends FSExecuteApp {
    override val application: String = s"pre_answer$MESSAGE_TERMINATOR"
  }

  /**
    * Record to a file from the channel's input media stream
    * Record is used to record voice messages, such as in a voicemail system. This application will record to a file specified by <path>.
    * After recording stops the record app sets the following read-only variables:
    *
    * record_ms — duration of most recently recorded file in milliseconds
    * record_samples — number of recorded samples
    * playback_terminator_used — TouchTone digit used to terminate recording
    *
    * @param filePath      : String An application will record to a file specified by file path.
    * @param timeLimitSecs : Duration it is the maximum duration of the recording in seconds
    * @param silenceThresh : Duration it is an energy level below which is considered silence.
    * @param silenceHits   : Duration it is how many seconds of audio below silence_thresh will be tolerated before the recording stops.
    *                      When omitted, the default value is 3 seconds
    * @param config        :ApplicationCommandConfig
    */
  final case class Record(
      filePath: String,
      timeLimitSecs: Duration,
      silenceThresh: Duration,
      silenceHits: Option[Duration],
      config: ApplicationCommandConfig
  ) extends FSExecuteApp {
    override val application: String = "record"
    override val args: String =
      s"$filePath ${timeLimitSecs.toSeconds} ${silenceThresh.toSeconds}${silenceHits
        .fold("")(f => s" ${f.toSeconds.toString}")}"
  }

  /**
    * Records an entire phone call or session.
    * Multiple media bugs can be placed on the same channel.
    *
    * @param filePathWithFormat : String file format like gsm,mp3,wav, ogg, etc
    * @param config             : ApplicationCommandConfig
    */
  final case class RecordSession(
      filePathWithFormat: String,
      config: ApplicationCommandConfig
  ) extends FSExecuteApp {
    override val application: String = "record_session"
    override val args: String = filePathWithFormat
  }

  /**
    * Send DTMF digits from the session using the method(s) configured on the endpoint in use
    * If no duration is specified the default DTMF length of 2000ms will be used.
    *
    * @param dtmfDigits   : String DTMF digits
    * @param toneDuration : Option[Duration]
    * @param config       : ApplicationCommandConfig
    */
  final case class SendDtmf(
      dtmfDigits: String,
      toneDuration: Option[Duration],
      config: ApplicationCommandConfig
  ) extends FSExecuteApp {
    override val application: String = "send_dtmf"
    override val args: String =
      s"$dtmfDigits${toneDuration.fold("")(f => s"@${f.toMillis.toString}")}"
  }

  /**
    * Stop record session.
    * Usage: <action application="stop_record_session" data="path"/>
    *
    * @param filePath : String file name
    * @param config   : ApplicationCommandConfig
    */
  final case class StopRecordSession(
      filePath: String,
      config: ApplicationCommandConfig
  ) extends FSExecuteApp {
    override val application: String = "stop_record_session"
    override val args: String = filePath
  }

  /**
    * Places a channel "on hold" in the switch, instead of in the phone. Allows for a number of different options, including:
    * Set caller in a place where the channel won't be hungup on, while waiting for someone to talk to.
    * Generic "hold" mechanism, where you transfer a caller to it.
    * Please note that to retrieve a call that has been "parked", you'll have to bridge to them or transfer the call to a valid location.
    * Also, remember that parking a call does NOT supply music on hold or any other media.
    * Park is quite literally a way to put a call in limbo until you you bridge/uuid_bridge or transfer/uuid_transfer it.
    * Note that the park application takes no arguments, so the data attribute can be omitted in the definition.
    *
    * @param config : ApplicationCommandConfig
    */
  final case class Park(config: ApplicationCommandConfig) extends FSExecuteApp {
    override val application: String = s"park$MESSAGE_TERMINATOR"
  }

  /**
    * Close the socket connection.
    *
    * @param config : ApplicationCommandConfig
    */
  final case class Exit(config: ApplicationCommandConfig) extends FSCommand {
    override def toString: String = s"exit$MESSAGE_TERMINATOR"
  }

  /**
    * Enable log output. Levels same as the console.conf values
    *
    * @param logLevel : String
    * @param config   : ApplicationCommandConfig
    */
  final case class Log(logLevel: String, config: ApplicationCommandConfig)
      extends FSCommand {
    override def toString: String = s"log $logLevel$MESSAGE_TERMINATOR"
  }

}

final case class CommandRequest(
    command: FSCommand,
    queueOfferResult: Future[QueueOfferResult]
)

sealed trait DialType extends Product with Serializable {
  val separator: String
}

/**
  * To dial multiple contacts all at once then separate targets by comma(,)
  */
case object AllAtOnce extends DialType {
  override val separator: String = ","
}

/**
  * To dial multiple contacts one at a time then separate targets by pipe(|)
  */
case object OneAtATime extends DialType {
  override val separator: String = "|"
}

/**
  *
  * @param min          Minimum number of digits to fetch.
  * @param max          Maximum number of digits to fetch.
  * @param soundFile    Sound file to play before digits are fetched.
  * @param variableName Channel variable that digits should be placed in.
  * @param timeout      Number of milliseconds to wait on each digit
  * @param terminators  Digits used to end input if less than <min> digits have been pressed. (Typically '#')
  */
final case class ReadParameters(
    min: Int,
    max: Int,
    soundFile: String,
    variableName: String,
    timeout: Duration,
    terminators: List[Char]
)
