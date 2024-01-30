/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.paimon.flink.action.cdc.format.debezium;

import org.apache.paimon.flink.action.cdc.TypeMapping;
import org.apache.paimon.flink.action.cdc.mysql.MySqlTypeUtils;
import org.apache.paimon.types.DataType;
import org.apache.paimon.types.DataTypes;
import org.apache.paimon.types.DecimalType;
import org.apache.paimon.utils.DateTimeUtils;
import org.apache.paimon.utils.StringUtils;

import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.databind.JsonNode;

import io.debezium.data.Bits;
import io.debezium.data.geometry.Geometry;
import io.debezium.data.geometry.Point;
import io.debezium.time.Date;
import io.debezium.time.MicroTime;
import io.debezium.time.MicroTimestamp;
import io.debezium.time.Timestamp;
import io.debezium.time.ZonedTimestamp;
import org.apache.kafka.connect.data.Decimal;
import org.apache.kafka.connect.json.JsonConverterConfig;

import javax.annotation.Nullable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;

import static org.apache.paimon.flink.action.cdc.TypeMapping.TypeMappingMode.TO_STRING;

/**
 * Utils to handle 'schema' field in debezium Json. TODO: The methods have many duplicate codes with
 * MySqlRecordParser. Need refactor.
 */
public class DebeziumSchemaUtils {

    /** Transform raw string value according to schema. */
    public static String transformRawValue(
            @Nullable String rawValue,
            String debeziumType,
            @Nullable String className,
            TypeMapping typeMapping,
            JsonNode origin) {
        if (rawValue == null) {
            return null;
        }

        String transformed = rawValue;

        if (Bits.LOGICAL_NAME.equals(className)) {
            // transform little-endian form to normal order
            // https://debezium.io/documentation/reference/stable/connectors/mysql.html#mysql-data-types
            byte[] littleEndian = Base64.getDecoder().decode(rawValue);
            byte[] bigEndian = new byte[littleEndian.length];
            for (int i = 0; i < littleEndian.length; i++) {
                bigEndian[i] = littleEndian[littleEndian.length - 1 - i];
            }
            if (typeMapping.containsMode(TO_STRING)) {
                transformed = StringUtils.bytesToBinaryString(bigEndian);
            } else {
                transformed = Base64.getEncoder().encodeToString(bigEndian);
            }
        } else if (("bytes".equals(debeziumType) && className == null)) {
            // MySQL binary, varbinary, blob
            transformed = new String(Base64.getDecoder().decode(rawValue));
        } else if ("bytes".equals(debeziumType) && Decimal.LOGICAL_NAME.equals(className)) {
            // MySQL numeric, fixed, decimal
            try {
                new BigDecimal(rawValue);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "Invalid big decimal value "
                                + rawValue
                                + ". Make sure that in the `customConverterConfigs` "
                                + "of the JsonDebeziumDeserializationSchema you created, set '"
                                + JsonConverterConfig.DECIMAL_FORMAT_CONFIG
                                + "' to 'numeric'",
                        e);
            }
        }
        // pay attention to the temporal types
        // https://debezium.io/documentation/reference/stable/connectors/mysql.html#mysql-temporal-types
        else if (Date.SCHEMA_NAME.equals(className)) {
            // MySQL date
            transformed = DateTimeUtils.toLocalDate(Integer.parseInt(rawValue)).toString();
        } else if (Timestamp.SCHEMA_NAME.equals(className)) {
            // MySQL datetime (precision 0-3)

            // display value of datetime is not affected by timezone, see
            // https://dev.mysql.com/doc/refman/8.0/en/datetime.html for standard, and
            // RowDataDebeziumDeserializeSchema#convertToTimestamp in flink-cdc-connector
            // for implementation
            LocalDateTime localDateTime =
                    DateTimeUtils.toLocalDateTime(Long.parseLong(rawValue), ZoneOffset.UTC);
            transformed = DateTimeUtils.formatLocalDateTime(localDateTime, 3);
        } else if (MicroTimestamp.SCHEMA_NAME.equals(className)) {
            // MySQL datetime (precision 4-6)
            long microseconds = Long.parseLong(rawValue);
            long microsecondsPerSecond = 1_000_000;
            long nanosecondsPerMicros = 1_000;
            long seconds = microseconds / microsecondsPerSecond;
            long nanoAdjustment = (microseconds % microsecondsPerSecond) * nanosecondsPerMicros;

            // display value of datetime is not affected by timezone, see
            // https://dev.mysql.com/doc/refman/8.0/en/datetime.html for standard, and
            // RowDataDebeziumDeserializeSchema#convertToTimestamp in flink-cdc-connector
            // for implementation
            LocalDateTime localDateTime =
                    Instant.ofEpochSecond(seconds, nanoAdjustment)
                            .atZone(ZoneOffset.UTC)
                            .toLocalDateTime();
            transformed = DateTimeUtils.formatLocalDateTime(localDateTime, 6);
        } else if (ZonedTimestamp.SCHEMA_NAME.equals(className)) {
            // MySQL timestamp

            // display value of timestamp is affected by timezone, see
            // https://dev.mysql.com/doc/refman/8.0/en/datetime.html for standard, and
            // RowDataDebeziumDeserializeSchema#convertToTimestamp in flink-cdc-connector
            // for implementation
            // TODO currently we cannot get zone id
            LocalDateTime localDateTime =
                    Instant.parse(rawValue).atZone(ZoneOffset.UTC).toLocalDateTime();
            transformed = DateTimeUtils.formatLocalDateTime(localDateTime, 6);
        } else if (MicroTime.SCHEMA_NAME.equals(className)) {
            long microseconds = Long.parseLong(rawValue);
            long microsecondsPerSecond = 1_000_000;
            long nanosecondsPerMicros = 1_000;
            long seconds = microseconds / microsecondsPerSecond;
            long nanoAdjustment = (microseconds % microsecondsPerSecond) * nanosecondsPerMicros;

            transformed =
                    Instant.ofEpochSecond(seconds, nanoAdjustment)
                            .atZone(ZoneOffset.UTC)
                            .toLocalTime()
                            .toString();
        } else if (Point.LOGICAL_NAME.equals(className)
                || Geometry.LOGICAL_NAME.equals(className)) {
            try {
                byte[] wkb = origin.get(Geometry.WKB_FIELD).binaryValue();
                transformed = MySqlTypeUtils.convertWkbArray(wkb);
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        String.format("Failed to convert %s to geometry JSON.", rawValue), e);
            }
        }

        return transformed;
    }

    public static DataType toDataType(
            String debeziumType, @Nullable String className, Map<String, String> parameters) {
        if (className != null) {
            switch (className) {
                case Bits.LOGICAL_NAME:
                    int length = Integer.parseInt(parameters.get("length"));
                    return DataTypes.BINARY((length + 7) / 8);
                case Decimal.LOGICAL_NAME:
                    String precision = parameters.get("connect.decimal.precision");
                    if (precision == null) {
                        return DataTypes.DECIMAL(20, 0);
                    }

                    int p = Integer.parseInt(precision);
                    if (p > DecimalType.MAX_PRECISION) {
                        return DataTypes.STRING();
                    } else {
                        int scale = Integer.parseInt(parameters.get("scale"));
                        return DataTypes.DECIMAL(p, scale);
                    }
                case Date.SCHEMA_NAME:
                    return DataTypes.DATE();
                case Timestamp.SCHEMA_NAME:
                    return DataTypes.TIMESTAMP(3);
                case MicroTimestamp.SCHEMA_NAME:
                case ZonedTimestamp.SCHEMA_NAME:
                    return DataTypes.TIMESTAMP(6);
                case MicroTime.SCHEMA_NAME:
                    return DataTypes.TIME();
            }
        }

        return fromDebeziumType(debeziumType);
    }

    private static DataType fromDebeziumType(String dbzType) {
        switch (dbzType) {
            case "int8":
                return DataTypes.TINYINT();
            case "int16":
                return DataTypes.SMALLINT();
            case "int32":
                return DataTypes.INT();
            case "int64":
                return DataTypes.BIGINT();
            case "float32":
                return DataTypes.FLOAT();
            case "double":
                return DataTypes.DOUBLE();
            case "boolean":
                return DataTypes.BOOLEAN();
            case "bytes":
                return DataTypes.BYTES();
            case "string":
            default:
                return DataTypes.STRING();
        }
    }
}
