# CDD-1578 Spike branch - persistence for notifications with resilience and send retry

It uses the following akka stack:

- `akka-persistence` - for persisting notifications
- `akka-cluster-sharding` - for resilience via multiple nodes. Note this in turn uses `akka-cluster` that in turn uses 
`akka-remoting`

It uses the same approach as the [akka example](https://github.com/typesafehub/activator-akka-cluster-sharding-scala)


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
 the mongo persistence journal.
         
In a new terminal type the following to start the request stream:
          
    curl -GET http://localhost:9000/start
              
In a new terminal type the following to compare sent with received at any time:
          
    curl -GET http://localhost:9000/end
              
To demonstrate resilience in terminal window 2 type `CRT-C` . Observe that notifications are still processed in terminal window 3
Type `sbt '; set javaOptions += "-Dpidfile.path=/dev/null" ; start 9821'` start up the node again. Observe that processing
 has been rebalanced to include the newly started node.
