# freeswitch-scala-esl - Work just started - not ready to use
A reactive event socket library for FreeSwitch written using Scala and Akka Streams

This library is intended to make the use of FreeSwitch easier for Scala developers. Once we complete we will consider implementing a custom esl module for freeswitch which implements akka streams back pressure to make the entire solution reactive. 

To use this library we are proposing an interface like this:-

# Inbound Mode

This mode will make a connection to FreeSwitch and connect using the password supplied. It will wait up to the duration supplied for a response before returning a Timeout exception to the returned future in the event it times out. 

```scala

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import esl.InboundServer
import esl.domain.FSMessage
import esl.parser.DefaultParser
import scala.concurrent.duration._

implicit val system = ActorSystem()
implicit val mater = ActorMaterializer()
implicit val ec = system.dispatcher

InboundServer("localhost", 8021, DefaultParser).connect("ClueCon", 2 seconds) {
  fsConnection =>
    fsConnection.map {
      f =>          
        //f will give you the actual connection in the case it is succesful, here you can start calling commands
         f.play("<play-file-path>")
    }
    Sink.foreach[List[FSMessage]](f => 
      //You need to return a Sink to handle a List[FSMessage] to fsConnection
      //In the example here we use a foreach Sink but you can materialise to 
      //actors, into other streams, etc. Any feature of Akka Streams
    )
}.onComplete {
  //you can use this onComplete for capturing the failure details such as Timeout or Invalid credentials for FreeSwitch 
  case Failure(ex) => {    
  }
}
```

# Outbound Mode

This will bind to a host / port in your system and wait for connections coming from freeswitch. You can see for an example we have changing the type of the flow using the Incoming flow higer order function. This is optional in this mode an Inbound mode and needed only if you want to do some work on the messages before you send to the Sink. Youc an just as well use the same as the Inbound example here and push a Sink[FSMessage] to the 

```scala
OutboundServer("localhost", 8080, DefaultParser).startWith(
    fsConnection => {
      //here you can call any command in freeswitch by calling the helper function with valid paramaters (play 
      //exampled below. or using sendCommand using a valid Command or a string of a valid FreeSwitch command. 
      fsConnection.play("<play-file-path>")
      fsConnection.sendCommand("full command string")
      Sink.foreach[List[String]](println)
      
      //ToDo handle closed connection
    },
    incomingFlow => {
      incomingFlow.map { freeSwitchMessages =>
        //here you get a Flow[FSMessage] which you can work with and return a Flow of a different type as per 
        freeSwitchMessages.map(_.contentType)
      }
    }
  )


```

I should credit https://github.com/danbarua/NEventSocket for some inspiration for this, I had used this library and found it useful in a .NET project and am taking some basic inspiration for design / structure from this. 
