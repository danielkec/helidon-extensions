# Oracle AQ connector

The `helidon-aq` connector connects Helidon Messaging channels to existing Oracle Advanced Queuing queues.
It is a standalone connector with its own configuration, lifecycle, settlement, and payload mapping.
It uses Oracle AQ's Java API and JDBC driver directly; it does not depend on either Helidon JMS connector.

## Dependency

Import the AQ extension BOM, then add:

```xml
<dependency>
    <groupId>io.helidon.extensions.messaging.connectors</groupId>
    <artifactId>helidon-extensions-messaging-connectors-aq</artifactId>
</dependency>
```

The application also needs the specification APIs used by Oracle AQ:
`jakarta.jms:jakarta.jms-api:2.0.3` (the `javax.jms` namespace) and
`javax.transaction:javax.transaction-api:1.3`.
These are specification API dependencies, not Helidon JMS connectors.

## Shared data source

Register the application-owned `javax.sql.DataSource` with Helidon's Service Registry. The connector resolves
the unqualified data source when no explicit connection resource is configured. Use `data-source-name` to
select a named data source. The same data source can serve multiple AQ channels and ordinary application JDBC code.
Default resource selection considers only unqualified services. Named data sources and connection factories require
an explicit name selection, so an unrelated named service cannot override the application's default AQ factory.

```yaml
messaging:
  connector:
    orders-aq:
      type: helidon-aq
      data-source-name: orders
      schema: ORDERS
  incoming:
    orders-in:
      connector: orders-aq
      destination: INCOMING_ORDERS
  outgoing:
    orders-out:
      connector: orders-aq
      destination: OUTGOING_ORDERS
```

Programmatic construction accepts the same data source directly:

```java
AqConnector connector = AqConnector.builder()
        .name("orders-aq")
        .dataSource(sharedDataSource)
        .schema("ORDERS")
        .build();
```

Every channel borrows its own connection and owns its own AQ session. Closing a channel releases its
connections and sessions; it does not close the data source or its pool. Sharing a data source does not
share a connection or transaction between channels or application code. Atomic database changes plus AQ
receive/send across application resources require a separate transaction integration.

As alternatives, configure an application-supplied Oracle AQ `ConnectionFactory`, its registry name, or a
`jdbc-url` with `username` and `password`. Resource choices are mutually exclusive. A resource selected at
channel level overrides the connector's resource selection as a unit. Configure wallets and JDBC/pool-specific
properties on the supplied data source or connection factory.

## Payloads and metadata

| Queue storage | Java payload | Configuration |
| --- | --- | --- |
| AQ JMS text | `String` | Default `payload-type: JMS` |
| AQ JMS bytes | `byte[]` | Default `payload-type: JMS` |
| AQ JMS map | `Map<String, Object>` with supported JMS map values | Default `payload-type: JMS` |
| AQ JMS object | `Serializable` | `allow-object-messages: true` |
| SQL object type | `AqAdt` | `payload-type: ADT` |
| `SYS.XMLTYPE` | `AqXml` | `payload-type: XML` |

The database queue's storage type must match the payload. The connector does not provision database queues,
types, users, or grants. Configured durable subscriptions are created through Oracle AQ.
An `AqPayloadConverter` can convert application payloads to and from
detached connector payloads without exposing a live JMS session or JDBC connection.

`AqMessage<T>` is a detached message envelope. It carries the payload, correlation identifier, JMS type,
reply-to destination, supported application properties, and explicit recipient list. Incoming metadata also
includes the provider message ID, timestamp, expiry, priority, delivery mode, redelivery state, delivery
attempts, and AQ sender when available. Availability depends on the queue payload and provider. Properties
are also exposed through Messaging headers. Native ADT/XML messages support AQ correlation and recipient
metadata but reject JMS type, reply-to, and application properties that their storage cannot represent.

```java
AqMessage<String> message = AqMessage.builder("order created")
        .correlationId("order-42")
        .putProperty("region", "EU")
        .addRecipient(AqRecipient.builder().name("BILLING").build())
        .addRecipient(AqRecipient.builder().name("SHIPPING").build())
        .build();
```

Recipient lists apply to multi-consumer queues, configured as `destination-type: TOPIC`. An incoming channel
can use `recipient-name` to consume for a named AQ recipient. Ordinary topic subscriptions can instead use
`durable: true` and `subscription-name`; durable subscriptions remain in the database after channel shutdown.
An empty outgoing recipient list uses AQ's configured subscribers. Incoming `message-selector` supports
provider selectors. `no-local` applies to ordinary topic subscriptions and cannot be combined with an explicit
recipient name.

Build SQL objects using their qualified database type name and ordered attributes:

```java
AqAdt event = AqAdt.builder()
        .typeName("ORDERS.ORDER_EVENT")
        .addAttribute("order-42")
        .addAttribute(BigDecimal.valueOf(12))
        .build();
AqXml xml = AqXml.builder().text("<order id=\"42\"/>").build();
```

`AqAdt.NullValue.INSTANCE` represents a SQL NULL attribute. Nested SQL objects and named arrays use nested
`AqAdt` values. XML is represented as text after the provider value is detached. Native objects requiring
unsupported JDBC attribute types fail conversion; they are not exposed as live JDBC objects.

SQL LOB attributes retain their type in detached `AqBlob` and `AqClob` values, including inside nested objects and arrays:

```java
AqBlob binary = AqBlob.builder().bytes(new byte[] {1, 2, 3}).build();
AqClob text = AqClob.builder().text("CLOB content").build();
AqClob nationalText = AqClob.builder().text("NCLOB content").national(true).build();
```

An incoming ADT can be forwarded with these values intact. The connector creates temporary LOBs on the sending
connection and releases them after use. Plain byte arrays and strings remain appropriate for RAW and character
attributes. `max-body-bytes` bounds binary LOB bytes and character LOB characters retained per incoming value.

Java object messages are disabled by default because deserialization executes application class code.
Enable them only for queues whose publishers and serialized classes the application trusts.

## Processing and settlement

An incoming channel reserves runtime delivery capacity before receiving. It receives one message, waits for
Messaging processing and failure handling to finish, then settles the delivery before receiving another.
There is no message `ack()`/`nack()` API and no independent polling executor.

The default `transacted: true` gives each channel an AQ local transaction. Successful incoming processing
commits the dequeue; unhandled failure rolls it back. Outgoing batches commit together on that channel,
and a send failure rolls back the batch. A commit failure reports an indeterminate outcome because the
client cannot prove whether the database committed. The Messaging runtime may retry or publish a dead
letter before reporting processing success; a successfully handled failure can therefore commit the input.

With `transacted: false`, incoming messages use client acknowledgement after processing, and outgoing sends
are individually visible. A batch is not a transaction across multiple outgoing channels. Already committed
outputs remain visible if another output fails.

Shutdown drains active processing before releasing channel resources. A configured timeout bounds graceful
cleanup; interrupted or abandoned deliveries remain unsettled. Database connection failures currently stop
the affected source or fail the send; automatic reconnect and replay are not implemented in this initial version.
Draining cancels an idle dequeue independently of `receive-timeout`, while an already received message is allowed
to finish processing and settlement before its session closes.

## Configuration defaults

Settings can be declared on the connector and overridden on individual channels.

| Setting | Default or requirement |
| --- | --- |
| `destination` | Required existing queue name |
| `schema` | Connected database user |
| `destination-type` | `QUEUE`; use `TOPIC` for multi-consumer queues |
| `payload-type` | `JMS`; alternatives `ADT`, `XML` |
| `transacted` | `true` |
| `allow-object-messages` | `false` |
| `receive-timeout` | `PT0.1S` |
| `close-timeout` | `PT10S` |
| `max-body-bytes` | `1048576`; bounds incoming bytes messages and each detached SQL LOB value |
| `durable` | `false`; when true requires `subscription-name` |
| `no-local` | `false` |
| `delivery-mode` | `2` (persistent), subject to Oracle queue support |
| `priority` | `4`, in range `0` through `9` |
| `time-to-live` | `PT0S` (no expiry) |

## Real database verification

The integration test module uses a running Oracle database and creates a uniquely named disposable schema.
Its administrator login must be able to create/drop users, grant AQ privileges, and grant tablespace allocation.
The fixture provisions queues from [`schema.sql`](../../tests/aq/src/test/resources/schema.sql) and removes
the schema afterward. It does not manage or stop the supplied database.

From the repository root:

```shell
mvn -B -ntp -f extensions/messaging/aq/pom.xml -Ptests verify \
    -Daq.test.url=jdbc:oracle:thin:@localhost:1521/FREEPDB1 \
    -Daq.test.user=system -Daq.test.password=YOUR_TEST_DATABASE_PASSWORD
```

Without `aq.test.url`, database integration tests are explicitly skipped. Unit tests still run.
XMLType integration additionally requires `-Daq.test.xml=true` and a database with a working XDB installation.
Oracle Free `23.26.3.0-lite` cannot create the XMLType queue because its XDB library is absent.
The complete database suite, including XMLType, is verified against the full Oracle Free `23.26.3.0` image
with Oracle AQ API `19.3.0.0` and JDBC/XDB `23.26.1.0.0`.
The database suite covers JMS payloads, selectors, explicit recipient lists, ADT/XMLType payloads,
durable subscriptions, generated `@Messaging.ReceiveFrom` handlers and injected emitters, runtime settlement,
rollback/redelivery, and sharing an injected data source across application components and runtime shutdown.
Other database releases and deployment-specific wallet/failover configurations need separate validation.
