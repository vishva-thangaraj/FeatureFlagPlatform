package com.feature_flag.platform.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.OpenApiCustomizer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Corrects two things springdoc infers wrongly for this API, so the TypeScript types the dashboard
 * generates from {@code /v3/api-docs} match what actually crosses the wire.
 *
 * <p><strong>Ids the client sends are strings.</strong> Path variables and request fields hold TSIDs as
 * {@code Long}, which springdoc describes as int64 numbers -- and a browser cannot hold a TSID in a number
 * without rounding it to the id of nothing (see {@code AuthResponse}). Every int64 whose name ends in
 * {@code Id} is described as a string of digits instead. The server is unchanged by this: Spring converts
 * the path segment and Jackson coerces a quoted number into the {@code Long}. Other int64s, such as
 * {@code accessTokenExpiresIn}, are durations and counts, and stay numbers.
 *
 * <p><strong>Response fields are present unless marked.</strong> Records carry no notion of required, so
 * every response field came out optional and the client had to guard each one. A field of a schema that
 * only appears in responses is now required, unless its record component says
 * {@code @Schema(nullable = true)}: such a field may be {@code null} or -- like
 * {@code AuthResponse.refreshToken} under cookie transport -- absent, and is described as optional and
 * nullable. Request bodies are left alone: their validation annotations already say what a caller must
 * send, so a schema that is also reached from a request is skipped too.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
final class DashboardContractCustomizer implements OpenApiCustomizer {

    private static final String ID_SUFFIX = "Id";
    private static final String NULL_TYPE = "null";

    @Override
    public void customise(OpenAPI openApi) {
        Map<String, Schema> schemas = openApi.getComponents() == null || openApi.getComponents().getSchemas() == null
                ? Map.of()
                : openApi.getComponents().getSchemas();
        List<Operation> operations = openApi.getPaths() == null
                ? List.of()
                : openApi.getPaths().values().stream().flatMap(path -> path.readOperations().stream()).toList();

        describeIdsAsStrings(openApi, operations, schemas);
        requirePresentResponseFields(operations, schemas);
    }

    private static void describeIdsAsStrings(OpenAPI openApi, List<Operation> operations, Map<String, Schema> schemas) {
        List<Parameter> parameters = new ArrayList<>();
        if (openApi.getPaths() != null) {
            for (PathItem path : openApi.getPaths().values()) {
                if (path.getParameters() != null) {
                    parameters.addAll(path.getParameters());
                }
            }
        }
        for (Operation operation : operations) {
            if (operation.getParameters() != null) {
                parameters.addAll(operation.getParameters());
            }
        }
        for (Parameter parameter : parameters) {
            if (isTsid(parameter.getName(), parameter.getSchema())) {
                describeAsDigits(parameter.getSchema());
            }
        }

        for (Schema schema : schemas.values()) {
            if (schema.getProperties() == null) {
                continue;
            }
            Map<String, Schema> properties = schema.getProperties();
            properties.forEach((name, property) -> {
                if (isTsid(name, property)) {
                    describeAsDigits(property);
                }
            });
        }
    }

    private static boolean isTsid(String name, Schema schema) {
        return name != null && name.endsWith(ID_SUFFIX)
                && schema != null && "int64".equals(schema.getFormat()) && hasType(schema, "integer");
    }

    /** Keeps {@code format: int64}: it still says what the digits are. */
    private static void describeAsDigits(Schema schema) {
        schema.setTypes(new LinkedHashSet<>(List.of("string")));
        schema.setType("string");
        schema.setPattern("^[0-9]+$");
    }

    private static void requirePresentResponseFields(List<Operation> operations, Map<String, Schema> schemas) {
        Set<String> fromResponses = new HashSet<>();
        Set<String> fromRequests = new HashSet<>();
        for (Operation operation : operations) {
            if (operation.getRequestBody() != null) {
                collectSchemaNames(operation.getRequestBody().getContent(), schemas, fromRequests);
            }
            if (operation.getResponses() != null) {
                for (ApiResponse response : operation.getResponses().values()) {
                    collectSchemaNames(response.getContent(), schemas, fromResponses);
                }
            }
        }
        fromResponses.removeAll(fromRequests);

        for (String name : fromResponses) {
            Schema schema = schemas.get(name);
            if (schema == null || schema.getProperties() == null) {
                continue;
            }
            List<String> required = schema.getRequired() == null
                    ? new ArrayList<>()
                    : new ArrayList<>(schema.getRequired());
            Map<String, Schema> properties = schema.getProperties();
            for (Map.Entry<String, Schema> property : properties.entrySet()) {
                if (Boolean.TRUE.equals(property.getValue().getNullable())) {
                    property.setValue(asNullable(property.getValue()));
                } else if (!required.contains(property.getKey())) {
                    required.add(property.getKey());
                }
            }
            schema.setRequired(required);
        }
    }

    /**
     * {@code nullable} is an OpenAPI 3.0 keyword; the document is 3.1, where null is one of the types. A
     * reference cannot take a second type, so it becomes "this schema or null".
     */
    private static Schema asNullable(Schema property) {
        property.setNullable(null);
        if (property.get$ref() != null) {
            return new Schema<>()
                    .specVersion(property.getSpecVersion())
                    .oneOf(List.of(
                            new Schema<>().specVersion(property.getSpecVersion()).$ref(property.get$ref()),
                            new Schema<>().specVersion(property.getSpecVersion()).types(Set.of(NULL_TYPE))));
        }
        Set<String> types = new LinkedHashSet<>();
        if (property.getTypes() != null) {
            types.addAll(property.getTypes());
        } else if (property.getType() != null) {
            types.add(property.getType());
        }
        types.add(NULL_TYPE);
        property.setTypes(types);
        return property;
    }

    private static void collectSchemaNames(Content content, Map<String, Schema> schemas, Set<String> names) {
        if (content == null) {
            return;
        }
        for (MediaType mediaType : content.values()) {
            collectSchemaNames(mediaType.getSchema(), schemas, names);
        }
    }

    private static void collectSchemaNames(Schema schema, Map<String, Schema> schemas, Set<String> names) {
        if (schema == null) {
            return;
        }
        if (schema.get$ref() != null) {
            String name = schema.get$ref().substring(schema.get$ref().lastIndexOf('/') + 1);
            if (names.add(name)) {
                collectSchemaNames(schemas.get(name), schemas, names);
            }
        }
        collectSchemaNames(schema.getItems(), schemas, names);
        if (schema.getAdditionalProperties() instanceof Schema additional) {
            collectSchemaNames(additional, schemas, names);
        }
        for (List<Schema> composed : List.of(
                nullToEmpty(schema.getAllOf()), nullToEmpty(schema.getOneOf()), nullToEmpty(schema.getAnyOf()))) {
            composed.forEach(part -> collectSchemaNames(part, schemas, names));
        }
        if (schema.getProperties() != null) {
            Map<String, Schema> properties = schema.getProperties();
            properties.values().forEach(property -> collectSchemaNames(property, schemas, names));
        }
    }

    private static boolean hasType(Schema schema, String type) {
        return type.equals(schema.getType()) || (schema.getTypes() != null && schema.getTypes().contains(type));
    }

    private static List<Schema> nullToEmpty(List<Schema> schemas) {
        return schemas == null ? List.of() : schemas;
    }
}
