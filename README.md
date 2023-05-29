# th2-conn-smtp(0.0.1)
This conn can send emails via smtp and retreives emails via pop3/imap

## Configuration

+ *clients* - list of client configurations
+ *maxBatchSize* - max size of outgoing message batch (`1000` by default)
+ *maxFlushTime* - max message batch flush time (`1000` by default)

## Client settings
+ *sessionAlias* - session alias for incoming/outgoing th2 messages
+ *from* - sender email address.
+ *to* - receiver email address.
+ *sender* - sender configuration.
+ *receiver* - receiver configuration.

### Sender configuration
+ *sessionConfiguration* - Represents the configuration settings for the session used by the sender. It includes properties such as the host, port, and other session-specific settings required for establishing a connection.
+ *authSettings* - Contains the authentication settings for the sender. It encapsulates information such as the username, password, or any other authentication credentials required to authenticate with the server.
+ *reconnectInterval* - Specifies the interval (in milliseconds) at which the sender will attempt to reconnect to the server if a connection is lost. The default value is 1000 milliseconds (1 second).

### Receiver configuration

+ *type* - Specifies the type of receiver. The default value is "pop3". Valid types include "pop3" and "imap".
+ *sessionConfiguration* - Represents the configuration settings for the session used by the receiver. It includes properties such as the host, port, and other session-specific settings required for establishing a connection.
+ *authSettings* - Contains the authentication settings for the receiver. It encapsulates information such as the username, password, or any other authentication credentials required to authenticate with the server.
+ *folder* - Specifies the folder name to retrieve messages from. The default value is "INBOX".
+ *fetchCount* - Specifies the maximum number of messages to fetch in a single retrieval. The default value is 1000.
+ *reconnectInterval* - Specifies the interval (in milliseconds) at which the receiver will attempt to reconnect to the server if a connection is lost. The default value is 1000 milliseconds (1 second).
+ *pollInterval* - Specifies the interval (in milliseconds) at which the receiver will poll the server for new messages. The default value is 1000 milliseconds (1 second).
+ *startProcessingAtLeastFrom* - Date from which messages should be processed. Format: [yyyy-MM-dd'T'HH:mm:ss]. Can be null.
+ *loadDatesFromCradle* - Specifies if receiver should use date from last processed message in cradle to resume processing where stopped last time.

### Session configuration
+ *host* - Specifies the hostname or IP address of the server. The default value is "localhost".
+ *port* - Specifies the port number to connect to on the server. The default value is 21.
+ *user* - Specifies the username for authentication with the server. The default value is "user".
+ *ssl* - Determines whether to use SSL/TLS for secure communication. The default value is `false`.
+ *startTls* - Determines whether to use the STARTTLS command for upgrading the connection to a secure channel. The default value is `false`.
+ *acceptAllCerts* - Determines whether to accept all server certificates without verification. This option is typically used for testing or development purposes. The default value is `false`.
+ *customCertificate* - Specifies a custom certificate to use for SSL/TLS communication.

### Custom Certificate
+ *certificateFilePath* - Specifies the file path to the certificate file. It represents the path to the certificate file that will be used for SSL/TLS communication.

### Auth settings
+ *username* - Specifies the username for authentication. The default value is "username".
+ *password* - Specifies the password for authentication. The default value is "password".

## Deployment via infra-mgr

Here's an example of `infra-mgr` config required to deploy this service

```yaml
apiVersion: th2.exactpro.com/v1
kind: Th2Box
metadata:
  name: fix-client
spec:
  image-name: ghcr.io/th2-net/th2-conn-dirty-fix
  image-version: 1.0.0
  type: th2-conn
  custom-config:
    clients:
    - sessionAlias: "sessionAlias1"
      from: "sender1@example.com"
      to: "receiver1@example.com"
      sender:
        sessionConfiguration:
          host: "smtp.example.com"
          port: 25
          user: "sender_user1"
          ssl: true
          startTls: false
          customCertificate:
            certificateFilePath: "/path/to/certificate1.pem"
          acceptAllCerts: false
        authSettings:
          username: "sender_user1"
          password: "sender_password1"
        reconnectInterval: 2000
      receiver:
        type: "pop3"
        sessionConfiguration:
          host: "pop3.example.com"
          port: 110
          user: "receiver_user1"
          ssl: false
          startTls: false
          customCertificate: null
          acceptAllCerts: false
        authSettings:
          username: "receiver_user1"
          password: "receiver_password1"
        folder: "INBOX"
        fetchCount: 500
        reconnectInterval: 1000
        pollInterval: 2000
    - sessionAlias: "sessionAlias2"
      from: "sender2@example.com"
      to: "receiver2@example.com"
      sender:
        sessionConfiguration:
          host: "smtp.example.com"
          port: 25
          user: "sender_user2"
          ssl: true
          startTls: false
          customCertificate:
            certificateFilePath: "/path/to/certificate2.pem"
          acceptAllCerts: false
        authSettings:
          username: "sender_user2"
          password: "sender_password2"
        reconnectInterval: 2000
      receiver:
        type: "imap"
        sessionConfiguration:
          host: "imap.example.com"
          port: 143
          user: "receiver_user2"
          ssl: false
          startTls: false
          customCertificate: null
          acceptAllCerts: false
        authSettings:
          username: "receiver_user2"
          password: "receiver_password2"
        folder: "INBOX"
        fetchCount: 500
        reconnectInterval: 1000
        pollInterval: 2000
    maxFlushTime: 1500
    maxBatchSize: 2000
  pins:
    - name: to_send
      connection-type: mq
      attributes:
        - subscribe
        - send
        - raw
    - name: incoming_messages
      connection-type: mq
      attributes:
        - publish
        - store
        - raw
      filters:
        - metadata:
            - field-name: direction
              expected-value: FIRST
              operation: EQUAL
    - name: outgoing_messages
      connection-type: mq
      attributes:
        - publish
        - store
        - raw
      filters:
        - metadata:
            - field-name: direction
              expected-value: SECOND
              operation: EQUAL
  extended-settings:
    externalBox:
      enabled: false
    service:
      enabled: false
    resources:
      limits:
        memory: 200Mi
        cpu: 600m
      requests:
        memory: 100Mi
        cpu: 20m
```

# Changelog
## 0.0.1
+ Initial support for smtp, pop3 and imap
