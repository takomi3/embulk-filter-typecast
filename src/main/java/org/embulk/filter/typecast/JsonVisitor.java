package org.embulk.filter.typecast;

import org.embulk.filter.typecast.TypecastFilterPlugin.ColumnConfig;
import org.embulk.filter.typecast.TypecastFilterPlugin.PluginTask;

import org.embulk.spi.Exec;
import org.embulk.spi.Schema;
import org.embulk.spi.type.Type;
import org.msgpack.value.ArrayValue;
import org.msgpack.value.MapValue;
import org.msgpack.value.Value;
import org.msgpack.value.ValueFactory;

import org.slf4j.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

public class JsonVisitor
{
    private static final Logger logger = Exec.getLogger(TypecastFilterPlugin.class);
    private final PluginTask task;
    private final Schema inputSchema;
    private final Schema outputSchema;
    private final HashSet<String> shouldVisitSet = new HashSet<>();
    private final HashMap<String, Type> jsonPathTypeMap = new HashMap<>();
    private final JsonCaster jsonCaster = new JsonCaster();

    JsonVisitor(PluginTask task, Schema inputSchema, Schema outputSchema)
    {
        this.task         = task;
        this.inputSchema  = inputSchema;
        this.outputSchema = outputSchema;

        buildShouldVisitSet();
        buildJsonPathTypeMap();
    }

    private void buildJsonPathTypeMap()
    {
        // json path => Type
        for (ColumnConfig columnConfig : task.getColumns()) {
            String name = columnConfig.getName();
            if (!name.startsWith("$.")) {
                continue;
            }
            Type type = columnConfig.getType();
            this.jsonPathTypeMap.put(name, type);
        }
    }

    private void buildShouldVisitSet()
    {
        // json partial path => Boolean to avoid unnecessary type: json visit
        for (ColumnConfig columnConfig : task.getColumns()) {
            String name = columnConfig.getName();
            if (!name.startsWith("$.")) {
                continue;
            }
            String[] parts = name.split("\\.");
            StringBuilder partialPath = new StringBuilder("$");
            for (int i = 1; i < parts.length; i++) {
                if (parts[i].contains("[")) {
                    String[] arrayParts = parts[i].split("\\[");
                    partialPath.append(".").append(arrayParts[0]);
                    this.shouldVisitSet.add(partialPath.toString());
                    for (int j = 1; j < arrayParts.length; j++) {
                        // Supports both [0] and [*]
                        partialPath.append("[").append(arrayParts[j]);
                        this.shouldVisitSet.add(partialPath.toString());
                    }
                }
                else {
                    partialPath.append(".").append(parts[i]);
                    this.shouldVisitSet.add(partialPath.toString());
                }
            }
        }
    }

    private boolean shouldVisit(String jsonPath)
    {
        return shouldVisitSet.contains(jsonPath);
    }

    public Value visit(String jsonPath, Value value)
    {
        if (!shouldVisit(jsonPath)) {
            return value;
        }
        if (value.isArrayValue()) {
            ArrayValue arrayValue = value.asArrayValue();
            int size = arrayValue.size();
            Value[] newValue = new Value[size];
            for (int i = 0; i < size; i++) {
                String k = new StringBuilder(jsonPath).append("[").append(Integer.toString(i)).append("]").toString();
                if (!shouldVisit(k)) {
                    k = new StringBuilder(jsonPath).append("[*]").toString(); // try [*] too
                }
                Value v = arrayValue.get(i);
                newValue[i] = visit(k, v);
            }
            return ValueFactory.newArray(newValue, true);
        }
        else if (value.isMapValue()) {
            MapValue mapValue = value.asMapValue();
            int size = mapValue.size() * 2;
            Value[] newValue = new Value[size];
            int i = 0;
            for (Map.Entry<Value, Value> entry : mapValue.entrySet()) {
                Value k = entry.getKey();
                Value v = entry.getValue();
                String newPath = new StringBuilder(jsonPath).append(".").append(k.asStringValue().asString()).toString();
                Value r = visit(newPath, v);
                newValue[i++] = k;
                newValue[i++] = r;
            }
            return ValueFactory.newMap(newValue, true);
        }
        else if (value.isBooleanValue()) {
            Type outputType = jsonPathTypeMap.get(jsonPath);
            return jsonCaster.fromBoolean(outputType, value.asBooleanValue());
        }
        else if (value.isIntegerValue()) {
            Type outputType = jsonPathTypeMap.get(jsonPath);
            return jsonCaster.fromLong(outputType, value.asIntegerValue());
        }
        else if (value.isFloatValue()) {
            Type outputType = jsonPathTypeMap.get(jsonPath);
            return jsonCaster.fromDouble(outputType, value.asFloatValue());
        }
        else if (value.isStringValue()) {
            Type outputType = jsonPathTypeMap.get(jsonPath);
            return jsonCaster.fromString(outputType, value.asStringValue());
        }
        else {
            return value;
        }
    }
}
