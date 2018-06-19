# CDD-1578 Spike branch - persistence for notifications with resilience and send retry

It uses the following akka stack:

- `akka-persistence` - for persisting notifications
- `akka-cluster-sharding` - for resilience via multiple nodes. Note this in turn uses `akka-cluster` that in turn uses 
`akka-remoting`

It uses the same sharding approach as the [akka example](https://github.com/typesafehub/activator-akka-cluster-sharding-scala)


## instructions

This spike uses the [customs-notifications-spike-client](https://github.com/googley42/customs-notifications-spike-client)
 project to generate requests for this endpoint (`customs-notification`).

open a new terminal window and cd to the location of `customs-notifications-spike-client` directory. Type:
 
    sbt run
     
open a new terminal window and cd to the location of this branch of `customs-notifications`. Type:
      
    sbt '; set javaOptions += "-Dpidfile.path=/dev/null" ; start 9821'
      
open a new terminal window and cd to the location of this branch of `customs-notifications`. Type:
      
     sbt '; set javaOptions += "-Dpidfile.path=/dev/null" ; set javaOptions += "-Dshard-node-port=2552" ; start 9800'

This starts the following

1. `customs-notifications-spike-client` on port `9000` to generate requests
2. An instance of `customs-notifications` on port `9821` without PID file generation and akka sharding started on port `2551` (default port)
3. An instance of `customs-notifications` on port `9822` without PID file generation and akka sharding started on port `2552`

Note the default behaviour of PID file generation was causing errors when running the 2nd instance.
Also note that we are using `sbt start` rather than `sbt run` as the later uses class reloading and this interfered with
 the mongo persistence journal ([see](https://github.com/scullxbones/akka-persistence-mongo/issues/45)).
```
[ERROR] [06/18/2018 11:41:34.576] [ClusterSystem-akka.actor.default-dispatcher-19] [akka.tcp://ClusterSystem@127.0.0.1:2551/system/sharding/Notifications/55/200b01f9-ec3b-4ede-b263-61b626dde232] Persistence failure when replaying events for persistenceId [Notifications-200b01f9-ec3b-4ede-b263-61b626dde232]. Last known sequence number [0]
java.lang.ClassNotFoundException: uk.gov.hmrc.customs.notification.actors.NotificationsActor$EnqueuedEvt
	at java.net.URLClassLoader.findClass(URLClassLoader.java:381)
	at java.lang.ClassLoader.loadClass(ClassLoader.java:424)
```
         
In a new terminal type the following to start the request stream:
          
    curl -GET http://localhost:9000/start
              
In a new terminal type the following to compare sent with received at any time:
          
    curl -GET http://localhost:9000/end
              
To demonstrate resilience in terminal window 2 type `CRT-C` . Observe that notifications are still processed in terminal window 3
Type `sbt '; set javaOptions += "-Dpidfile.path=/dev/null" ; start 9821'` start up the node again. Observe that processing
 has been rebalanced to include the newly started node.

# TODO:

## For production - investigate how replace static `akka-cluster` config `seed-nodes` with dynamic lookup. 

[Offical doc says](https://doc.akka.io/docs/akka/current/cluster-usage.html#automatically-joining-to-seed-nodes-with-cluster-bootstrap)
"Automatically joining to seed nodes with Cluster Bootstrap
 Instead of manually configuring seed nodes, which is useful in development or statically assigned node IPs, you may want to 
 automate the discovery of seed nodes using your cloud providers or cluster orchestrator, or some other form of service discovery 
 (such as managed DNS). The open source Akka Management library includes the 
 [Cluster Bootstrap](https://developer.lightbend.com/docs/akka-management/current/bootstrap.html) module which handles just that. 
 Please refer to its documentation for more details."
 
## For production - get reactivemogo version of persistence journal working???
 
I used a [mongo persistence journal](https://github.com/scullxbones/akka-persistence-mongo/blob/master/docs/akka24.md)
This comes in two flavors:
- `Casbah` 
- `reactivemongo`
However I had to use the `Casbah` version of this driver as the `reactivemongo` flavor pulls in AKKA and I hit runtime problems
 with incompatible versions of AKKA classes so I switched to the `Casbah` version due to time constraints. 
MDTP services as whole use `reactivemongo`. Is `Casbah` blocking (I seem to remember as so)? we need to check.     

## akka serialiser 
Use this rather than default Java serialiser for efficiency and to get rid of warnings about this

## On send notification failure enqueue unsent notifications to push queue

This has not been spiked yet.

## Figure out how to use [Multi Note Testing test kit](https://doc.akka.io/docs/akka/2.5/multi-node-testing.html)

[There is an example of this in](https://github.com/typesafehub/activator-akka-cluster-sharding-scala) 



