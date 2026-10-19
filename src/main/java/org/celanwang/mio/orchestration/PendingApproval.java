package org.celanwang.mio.orchestration;

import java.util.Map;

/** Opaque public approval id maps to a specific child call and immutable parameter fingerprint. */
public record PendingApproval(String id, String callId, String tool, String description,
                              Map<String,Object> input, String inputHash, String contractHash, long expiresAt, Boolean decision) {
    public PendingApproval {
        input = freezeMap(input);
    }
    private static Map<String,Object> freezeMap(Map<?,?> source) {
        var copy = new java.util.LinkedHashMap<String,Object>();
        source.forEach((key,value)->{
            if (!(key instanceof String name)) throw new IllegalArgumentException("工具参数的键必须为字符串");
            copy.put(name,freeze(value));
        });
        return java.util.Collections.unmodifiableMap(copy);
    }
    private static Object freeze(Object value) {
        if(value instanceof Map<?,?> map)return freezeMap(map);
        if(value instanceof java.util.List<?> list)return java.util.Collections.unmodifiableList(list.stream().map(PendingApproval::freeze).toList());
        if(value==null||value instanceof String||value instanceof Boolean||value instanceof Integer||value instanceof Long
                ||value instanceof Double||value instanceof Float||value instanceof Short||value instanceof Byte
                ||value instanceof java.math.BigDecimal||value instanceof java.math.BigInteger)return value;
        throw new IllegalArgumentException("工具参数必须是 JSON 值");
    }
    public PendingApproval decide(boolean approved) {
        return new PendingApproval(id,callId,tool,description,input,inputHash,contractHash,expiresAt,approved);
    }
}
