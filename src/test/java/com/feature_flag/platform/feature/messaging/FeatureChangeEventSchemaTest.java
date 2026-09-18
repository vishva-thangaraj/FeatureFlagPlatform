package com.feature_flag.platform.feature.messaging;

import com.feature_flag.platform.feature.events.FeatureChangeEvent;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.generic.GenericRecordBuilder;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the two schema-evolution properties that are invisible until the day they break production:
 * the namespace rename is bridged by aliases, and an unknown change type degrades instead of
 * failing to decode.
 */
class FeatureChangeEventSchemaTest {

    private static final String OLD_NAMESPACE = "com.feature_flag.platform.events";

    /** The schema as it was before commit 1208851 moved the namespace. */
    private static final String PRE_RENAME_SCHEMA = """
            {
              "type": "record",
              "name": "FeatureChangeEvent",
              "namespace": "com.feature_flag.platform.events",
              "fields": [
                { "name": "eventId", "type": "string" },
                { "name": "changeType", "type": { "type": "enum", "name": "ChangeType",
                  "symbols": ["STATUS_CHANGED","RULE_ADDED","RULE_UPDATED","RULE_DELETED",
                    "RULES_REPLACED","OVERRIDE_SET","OVERRIDE_DELETED"] }},
                { "name": "tenantId",      "type": "string" },
                { "name": "featureKey",    "type": "string" },
                { "name": "envName",       "type": "string" },
                { "name": "occurredAt", "type": {"type":"long","logicalType":"timestamp-millis"} },
                { "name": "actorUserId", "type": ["null", "string"], "default": null },
                { "name": "details", "type": { "type": "map", "values": "string" }, "default": {} }
              ]
            }
            """;

    /** A hypothetical future schema that adds an eighth change type. */
    private static final String SCHEMA_WITH_FUTURE_CHANGE_TYPE = PRE_RENAME_SCHEMA
            .replace("\"com.feature_flag.platform.events\"", "\"com.feature_flag.platform.feature.events\"")
            .replace("\"OVERRIDE_DELETED\"", "\"OVERRIDE_DELETED\",\"FEATURE_ARCHIVED\"");

    private static Schema current() {
        return FeatureChangeEvent.getClassSchema();
    }

    @Test
    @DisplayName("the record and its enum carry aliases for their pre-rename full names")
    void aliasesBridgeTheNamespaceRename() {
        assertThat(current().getAliases())
                .as("without this alias a registry holding the old schema rejects this one with a 409")
                .contains(OLD_NAMESPACE + ".FeatureChangeEvent");
        assertThat(current().getField("changeType").schema().getAliases())
                .contains(OLD_NAMESPACE + ".ChangeType");
    }

    @Test
    @DisplayName("a record written under the old namespace still decodes with the current schema")
    void recordWrittenBeforeTheRenameIsStillReadable() throws Exception {
        Schema oldSchema = new Schema.Parser().parse(PRE_RENAME_SCHEMA);
        GenericRecord written = new GenericRecordBuilder(oldSchema)
                .set("eventId", "evt-1")
                .set("changeType", new GenericData.EnumSymbol(
                        oldSchema.getField("changeType").schema(), "RULE_ADDED"))
                .set("tenantId", "42")
                .set("featureKey", "checkout_v2")
                .set("envName", "prod")
                .set("occurredAt", 1_700_000_000_000L)
                .set("actorUserId", "user-7")
                .set("details", Map.of("ruleId", "99"))
                .build();

        GenericRecord read = roundTrip(written, oldSchema, current());

        assertThat(read.get("tenantId")).hasToString("42");
        assertThat(read.get("featureKey")).hasToString("checkout_v2");
        assertThat(read.get("changeType")).hasToString("RULE_ADDED");
    }

    @Test
    @DisplayName("ChangeType declares UNKNOWN as its default so a future symbol degrades gracefully")
    void unknownChangeTypeDecodesAsTheEnumDefault() throws Exception {
        Schema changeType = current().getField("changeType").schema();
        assertThat(changeType.getEnumDefault()).hasToString("UNKNOWN");

        Schema futureSchema = new Schema.Parser().parse(SCHEMA_WITH_FUTURE_CHANGE_TYPE);
        GenericRecord written = new GenericRecordBuilder(futureSchema)
                .set("eventId", "evt-2")
                .set("changeType", new GenericData.EnumSymbol(
                        futureSchema.getField("changeType").schema(), "FEATURE_ARCHIVED"))
                .set("tenantId", "42")
                .set("featureKey", "checkout_v2")
                .set("envName", "prod")
                .set("occurredAt", 1_700_000_000_000L)
                .set("actorUserId", null)
                .set("details", Map.of())
                .build();

        GenericRecord read = roundTrip(written, futureSchema, current());

        assertThat(read.get("changeType"))
                .as("a consumer on today's schema must degrade to UNKNOWN, not fail to deserialize")
                .hasToString("UNKNOWN");
        assertThat(read.get("featureKey")).hasToString("checkout_v2");
    }

    private static GenericRecord roundTrip(GenericRecord record, Schema writerSchema, Schema readerSchema)
            throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(out, null);
        new GenericDatumWriter<GenericRecord>(writerSchema).write(record, encoder);
        encoder.flush();
        return new GenericDatumReader<GenericRecord>(writerSchema, readerSchema)
                .read(null, DecoderFactory.get().binaryDecoder(out.toByteArray(), null));
    }
}
