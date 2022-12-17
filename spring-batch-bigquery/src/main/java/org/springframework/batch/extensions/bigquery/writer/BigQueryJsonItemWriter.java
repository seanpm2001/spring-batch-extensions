/*
 * Copyright 2002-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.batch.extensions.bigquery.writer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.google.cloud.bigquery.Table;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.convert.converter.Converter;
import org.springframework.util.Assert;
import org.springframework.util.ObjectUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class BigQueryJsonItemWriter<T> extends BigQueryBaseItemWriter<T> implements InitializingBean {

    protected Converter<T, byte[]> rowMapper;
    protected ObjectWriter objectWriter;
    protected Class itemClass;

    @Override
    protected void doInitializeProperties(List<? extends T> items) {
        if (Objects.isNull(this.itemClass)) {
            T firstItem = items.stream().findFirst().orElseThrow(RuntimeException::new);
            this.itemClass = firstItem.getClass();

            if (Objects.isNull(this.rowMapper)) {
                this.objectWriter = new ObjectMapper().writerFor(this.itemClass);
            }

            super.logger.debug("Writer setup is completed");
        }
    }

    public void setRowMapper(Converter<T, byte[]> rowMapper) {
        this.rowMapper = rowMapper;
    }

    @Override
    protected List<byte[]> convertObjectsToByteArrays(List<? extends T> items) {
        return items
                .stream()
                .map(this::mapItemToJson)
                .filter(ArrayUtils::isNotEmpty)
                .map(String::new)
                .map(this::convertToNdJson)
                .filter(value -> !ObjectUtils.isEmpty(value))
                .map(row -> row.getBytes(StandardCharsets.UTF_8))
                .collect(Collectors.toList());
    }

    @Override
    public void afterPropertiesSet() {
        super.baseAfterPropertiesSet(() -> {
            Table table = getTable();

            if (BooleanUtils.toBoolean(super.writeChannelConfig.getAutodetect())) {
                if ((tableHasDefinedSchema(table) && super.logger.isWarnEnabled())) {
                    super.logger.warn("Mixing autodetect mode with already defined schema may lead to errors on BigQuery side");
                }
            } else {
                Assert.notNull(super.writeChannelConfig.getSchema(), "Schema must be provided");

                if (tableHasDefinedSchema(table)) {
                    Assert.isTrue(
                            table.getDefinition().getSchema().equals(super.writeChannelConfig.getSchema()),
                            "Schema should be the same"
                    );
                }
            }

            return null;
        });
    }

    protected byte[] mapItemToJson(T t) {
        byte[] result = null;
        try {
            result = Objects.isNull(rowMapper) ? objectWriter.writeValueAsBytes(t) : rowMapper.convert(t);
        }
        catch (JsonProcessingException e) {
            logger.error("Error during processing of the line: ", e);
        }
        return result;
    }

    /**
     * BigQuery uses ndjson https://github.com/ndjson/ndjson-spec.
     * It is expected that to pass here JSON line generated by
     * {@link com.fasterxml.jackson.databind.ObjectMapper} or any other JSON parser.
     */
    private String convertToNdJson(String json) {
        return json.concat(org.apache.commons.lang3.StringUtils.LF);
    }

}
