## Introduction
This project is an Event server designed to be deployed to a Server with java-8 and handle
websocket connections.

It can be run directly using the gradle 'run' task

```./gradlew run```

The build.gradle uses the Gradle shadowJar plugin to assemble the application and all it's dependencies into a single "fat" jar.

```./gradlew shadowJar```

This jar can run anywhere with a Java 8+. To run the fat jar:

```java -jar build/libs/eventserver.jar```

The event-server now runs on [http://localhost:6969](http://localhost:6969).
The 'server.mode' option can be set to TEST or TEST_CLIENT to get a test-page at that address.
In PROD-Mode (default) only the REST- and SockJS-endpoints are active.

## Concept

#### Receiving
* A user connects to the chat-website on an application-server
* The page contains a sockjs-client similar to the included index.html
* The url for the SockJS-client points to the event-servers /socket endpoint and contains a *token* query-parameter. e.g. ```http://eventserver.domain.com/socket?token=TOKEN```
* The event-server accepts the websocket-connection and sends a GET request with the token to the application-server's ```/authChat``` endpoint
* The application-server responds with the user-id for the token and the list of channels the user belongs to
* The event-server sends the last 200 channel-messages (configurable) to the user unless the channel-name starts with an ```!```
* The channel is set up to send the user future messages
* The definition of the ```/chatAuth``` endpoint can be found in the [auth-server swagger.yaml file](swagger/auth-server/swagger.yaml)

#### Sending
* Messages are only received via the application-server - not by the user directly
* If the message contains target user-ids the message is sent directly to the users
* Otherwise the message is sent to the channel and stored
* The definition of the ```/send``` endpoint can be found in the [event-server swagger.yaml file](swagger/event-server/swagger.yaml)

#### Updating channels
* The ```/updateUsers``` endpoing can be used to update user's channel-subscriptions
* The user sending "update" via the SockJS socket will trigger a re-authentication and update of channel-subscroptions
* The definition of the ```/updateUsers``` endpoint can be found in the [event-server swagger.yaml file](swagger/event-server/swagger.yaml)

#### Slack-integration
* Slack integration can optionally be configured
* Messages sent to specified channels are transmitted to a slack incoming-webhook using a channel-mapping
* Messages received on the /slack endpoint via a slack outgoing-webhook are mapped into the system via a separate channel-map
* Messages from bot-users in slack are ignored to prevent loops
* The definition of the ```/slack``` endpoint can be found in the [event-server swagger.yaml file](swagger/event-server/swagger.yaml)

#### Clustering
* The event-server starts up with hazelcast and will automatically try to span a cluster
* Messages are distributed throughout the cluster
* Only one server in the cluster can send slack-messages. This is done automatically. Failover checks happen every 60s.


## Message-Format
#### Example for a message frame received on SockJS
```json
{
    "error": "Error message, only in the frame if an error occured",
    "messages": [
        {
            "senderUserId": "sender01",
            "senderDisplayName": "Mr Sender",
            "channel": "public",
            "text": "The text of the message",
            "timestamp": 1200000,
            "additionalData": {}
        }
    ]
}
```
#### Other
See [event-server swagger.yaml file](swagger/event-server/swagger.yaml)


## Config-Options
 Option                   | Type/Values         | Default value | Description
--------------------------|---------------------|---------------|---------------
cluster.host              |String               |               |Address of this machine. Might be required in case of multiple interfaces
hazelcast.group.name      |String               |dev            |Hazelcast group
hazelcast.group.password  |String               |dev-pass       |Hazelcast group password
hazelcast.management.url  |String               |               |URL of a Hazelcast management-server
server.mode               |PROD/TEST/TEST_CLIENT|PROD           |Mode the server runs in. TEST/TEST_CLIENT provide the index.html. TEST_CLIENT mocks the authentication
server.port               |int                  |6969           |THe port the server runs on
cors.origins              |String               |*              |CORS header origin to use. Can be set more strictly for better security
log.connections           |boolean              |false          |Activate connection-logging. This can produce a lot of log-entries
log.storage               |boolean              |false          |Activate storage-logging. This can produce a lot of very long log-entries
channel.history.size      |int                  |200            |Amount of entries to keep per channel. These entries are sent to a client on connection.
api.header.name           |String               |api_key        |Header-Name for the API-Key for the /send endpoint
api.header.value          |String               |               |Heaver-Value for the API-Key for the /send endpoint
remote.host               |String               |localhost      |Hostname of the application-server with a chatAuth endpoint
remote.port               |int                  |8886           |Port of the application-server
remote.ssl                |boolean              |false          |If true the event-server will use HTTPS to connect to the application-server
remote.auth.endpoint      |String               |/chatAuth      |Name of the chatAuth-endpoint
remote.api.header.name    |String               |api_key        |Header-Name for the API-Key for the chatAuth endpoint on the application-server
remote.api.header.value   |String               |               |Header-Value for the API-Key for the chatAuth endpoint on the application-server
slack.enabled             |boolean              |false          |Enables slack integration
slack.url                 |String               |               |URL to a slack incoming-webhook
slack.channels.incoming   |JsonObject           |               |Map Slack-Channel -> EventServer-Channel
slack.channels.outgoing   |JsonObject           |               |Map Event-Server-Channel -> Slack-Channel
slack.username            |String               |               |Username to use for sending messages to a slack incoming-webhook
slack.token               |String               |               |Token that the event-server can use to authenticate a slack outgoing-webhook
redis.enabled             |boolean              |false          |Enables Redis for storing history
redis.host                |String               |redis          |Redis Hostname. Only relevant if redis.enabled=true
redis.port                |int                  |6379           |Redis Port. Only relevant if redis.enabled=true
redis.history.expire      |long                 |6*60*60        |Seconds since the last update until redis expires all history for a volatile channel (channel starting with ?)
