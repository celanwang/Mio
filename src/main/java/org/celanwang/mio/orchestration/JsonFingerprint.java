package org.celanwang.mio.orchestration;

import com.fasterxml.jackson.databind.*;
import java.security.MessageDigest;
import java.util.HexFormat;

final class JsonFingerprint {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static JsonNode sorted(JsonNode node){
        if(node.isObject()){
            var result=JSON.createObjectNode();var names=new java.util.TreeSet<String>();node.fieldNames().forEachRemaining(names::add);
            names.forEach(n->result.set(n,sorted(node.get(n))));return result;
        }
        if(node.isArray()){var result=JSON.createArrayNode();node.forEach(n->result.add(sorted(n)));return result;}
        return node;
    }
    static String hash(Object input){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(sorted(JSON.valueToTree(input)))));}
        catch(Exception e){throw new IllegalStateException("参数指纹失败",e);}
    }
}
