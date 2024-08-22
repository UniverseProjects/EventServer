# Project Summary

## Overview
This project is an event server that handles communication between different services using various protocols such as Slack and Discord. It includes functionalities for processing incoming messages, sending messages to different channels, managing user sessions, and storing chat history.

## Languages, Frameworks, and Main Libraries
- **Languages:** Java
- **Frameworks:** Vert.x
- **Main Libraries:** Hazelcast, Lettuce, Prometheus

## Purpose
The project serves as a central event server for handling communication between different services using various protocols. It manages user sessions, processes incoming messages, and stores chat history.

## Relevant Files
1. **CommunicationService.java:** `/src/main/java/com/universeprojects/eventserver/CommunicationService.java`
2. **Config.java:** `/src/main/java/com/universeprojects/eventserver/Config.java`
3. **DiscordCommunicationService.java:** `/src/main/java/com/universeprojects/eventserver/DiscordCommunicationService.java`
4. **EscapingService.java:** `/src/main/java/com/universeprojects/eventserver/EscapingService.java`
5. **EventServerVerticle.java:** `/src/main/java/com/universeprojects/eventserver/EventServerVerticle.java`
6. **HazelcastHistoryService.java:** `/src/main/java/com/universeprojects/eventserver/HazelcastHistoryService.java`
7. **HealthCheckHandler.java:** `/src/main/java/com/universeprojects/eventserver/HealthCheckHandler.java`
8. **HistoryService.java:** `/src/main/java/com/universeprojects/eventserver/HistoryService.java`
9. **IncomingMessageHandler.java:** `/src/main/java/com/universeprojects/eventserver/IncomingMessageHandler.java`
10. **Main.java:** `/src/main/java/com/universeprojects/eventserver/Main.java`
11. **RedisChatCodec.java:** `/src/main/java/com/universeprojects/eventserver/RedisChatCodec.java`
12. **RedisHistoryService.java:** `/src/main/java/com/universeprojects/eventserver/RedisHistoryService.java`
13. **SessionService.java:** `/src/main/java/com/universeprojects/eventserver/SessionService.java`
14. **SharedDataService.java:** `/src/main/java/com/universeprojects/eventserver/SharedDataService.java`
15. **SlackCommunicationService.java:** `/src/main/java/com/universeprojects/eventserver/SlackCommunicationService.java`
16. **SockJSSocketHandler.java:** `/src/main/java/com/universeprojects/eventserver/SockJSSocketHandler.java`
17. **UpdateUsersHandler.java:** `/src/main/java/com/universeprojects/eventserver/UpdateUsersHandler.java`
18. **User.java:** `/src/main/java/com/universeprojects/eventserver/User.java`
19. **UserService.java:** `/src/main/java/com/universeprojects/eventserver/UserService.java`
20. **VersionHandler.java:** `/src/main/java/com/universeprojects/eventserver/VersionHandler.java`

## Source Files Directory
- **Source Files:** `/src/main/java/com/universeprojects/eventserver/`

## Documentation Files Location
- **Documentation Files:** `/src/main/resources/`

This project provides a robust event server for managing communication between different services efficiently.