# HTTP-WebSocket Client Template v0.0.2

This is a template project which combines [HTTP](https://github.com/th2-net/th2-conn-http-client)
and [WebSocket](https://github.com/th2-net/th2-conn-ws-client) clients together.

## Purpose

It can be used to implement a typical scenario where an init-step is done via HTTP (e.g. Login) and then the data from this step (e.g. cookies) is used to establish a WebSocket connection. When WebSocket connection dies or if init-step
fails, this sequence will be performed again if client itself is in started-state. This behavior is implemented via hybrid-handler which is shared between HTTP and WebSocket clients where such init-step can be implemented in `preOpen`
method.

## Configuration

The main configuration is done by changing following properties:

+ **autoStart** - start service automatically (`true` by default and if `startControl` is `false`)
+ **autoStopAfter** - stop after N seconds if the service was started automatically prior to send (`0` by default which means disabled)
+ **grpcStartControl** - enables start/stop control via [gRPC service](https://github.com/th2-net/th2-grpc-conn/blob/37c8ee21135225a6140bd459ec617c6fa5c04b3b/src/main/proto/th2_grpc_conn/conn.proto#L22) (`false` by default)
+ **http** - HTTP connection configuration block
+ **ws** - WebSocket connection configuration block

### HTTP connection configuration

+ **https** - enables HTTPS (`false` by default)
+ **host** - host for HTTP requests (e.g. `google.com`)
+ **port** - port for HTTP requests (`80` by default or `443` if `https` = `true`)
+ **readTimeout** - socket read timeout in ms (`5000` by default)
+ **keepAliveTimeout** - socket inactivity timeout in ms (`15000` by default)
+ **defaultHeaders** - map of default headers, and their values which will be applied to each request (existing headers are not affected, empty by default)
+ **sessionAlias** - session alias for incoming/outgoing HTTP messages (e.g. `rest_api`)

### WebSocket connection configuration

+ **uri** - connection URI
+ **frameType** - outgoing WebSocket frame type, can be either `TEXT` or `BINARY` (`TEXT` by default)
+ **pingInterval** - interval for sending ping-messages in ms (`30000` by default)
+ **sessionAlias** - session alias for incoming/outgoing WS messages (e.g. `ws_api`)

Service will also automatically connect prior to message send if it wasn't connected

### Configuration example

```yaml
autoStart: true
autoStopAfter: 0
grpcStartControl: false
ws:
  uri: wss://echo.websocket.org
  frameType: TEXT
  pingInterval: 30000
  sessionAlias: api_session_ws
http:
  https: false
  host: someapi.com
  port: 334
  sessionAlias: api_session_http
  readTimeout: 5000
  keepAliveTimeout: 15000
  defaultHeaders: [ ]
```

### MQ pins

* input queue with `subscribe` and `http_send` attributes for outgoing HTTP requires
* input queue with `subscribe` and `ws_send` attributes for outgoing WebSocket messages
* output queue with `publish`, `first`, `http_request` for sent HTTP requests
* output queue with `publish`, `second`, `http_response` for sent HTTP responses
* output queue with `publish`, `first`, `ws_incoming` for received WebSocket messages
* output queue with `publish`, `second`, `ws_outgoing` for sent WebSocket messages

## Inputs/outputs

This section describes the messages received and produced by the service

### Inputs (WS)

This service receives messages that will be sent via WS as `MessageGroup`s, containing a single `RawMessage` with a message body

### Inputs (HTTP)

This service receives HTTP requests as `MessageGroup`s containing one of:

* a single `RawMessage` containing request body, which can have `uri`, `method`, and `contentType` properties in its metadata, which will be used in resulting request
* a single `Message` with `Request` message type containing HTTP request line and headers and a `RawMessage` described above

If both `Message` and `RawMessage` contain `uri`, `method`, and `contentType`, values from `Message` take precedence.  
If none of them contain these values `/` and `GET` will be used as `uri` and `method` values respectively

### Outputs (WS/HTTP)

Incoming and outgoing messages are sent via MQ as `MessageGroup`s, containing a single `RawMessage` with a message body.

## Deployment via `infra-mgr`

Here's an example of `infra-mgr` config required to deploy this service

```yaml
apiVersion: th2.exactpro.com/v1
kind: Th2Box
metadata:
  name: ws-client
spec:
  image-name: ghcr.io/th2-net/th2-conn-http-ws-client
  image-version: 0.0.2
  custom-config:
    autoStart: true
    autoStopAfter: 0
    grpcStartControl: false
    ws:
      uri: wss://echo.websocket.org
      frameType: TEXT
      pingInterval: 30000
      sessionAlias: api_session_ws
    http:
      https: false
      host: someapi.com
      port: 334
      sessionAlias: api_session_http
      readTimeout: 5000
      keepAliveTimeout: 15000
      defaultHeaders: [ ]
  type: th2-conn
  pins:
    - name: to_send_http
      connection-type: mq
      attributes:
        - subscribe
        - http_send
    - name: to_send_ws
      connection-type: mq
      attributes:
        - subscribe
        - ws_send
    - name: http_requests
      connection-type: mq
      attributes:
        - publish
        - second
        - raw
        - http_request
    - name: http_responses
      connection-type: mq
      attributes:
        - publish
        - first
        - raw
        - http_response
    - name: incoming_ws_messages
      connection-type: mq
      attributes:
        - publish
        - first
        - raw
        - ws_incoming
    - name: outgoing_ws_messages
      connection-type: mq
      attributes:
        - publish
        - second
        - raw
        - ws_outgoing
```