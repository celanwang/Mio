package org.celanwang.mio.orchestration;

import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.util.*;

/** Consumes the real bridge envelope. This snapshot is context, never a transferable booking token. */
public final class TencentQuoteFactExtractor implements FactExtractor {
    private final ObjectMapper json=new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    @Override public String source(){return "tencent-quote-v1";}
    @Override public Optional<String> invalidatedKey(String tool,Map<String,Object> input){
        if("tencent_ride_create_order".equals(tool))return Optional.of("ride.quote");
        if(!"tencent_ride".equals(tool))return Optional.empty();
        return Set.of("estimate-price","poi-search","select-poi","addr-sync-to-state","delete-state","set-resident-city",
                "addr-upsert-value","short-cut-upsert-value","cancel-order").contains(input.getOrDefault("command",""))?Optional.of("ride.quote"):Optional.empty();
    }
    @Override public Optional<Value> extract(String tool,Map<String,Object> input,String raw,long now){
        if(!"tencent_ride".equals(tool)||!"estimate-price".equals(input.get("command")))return Optional.empty();
        try{
            var root=json.readTree(raw);var output=root.path("output");var result=output.path("result");var body=result.path("body");var data=body.path("data");
            if(!integer(root.path("exitCode"),0)||!integer(result.path("status"),200)||!"estimated".equals(output.path("mode").asText())
                    ||!(integer(body.path("code"),0)||body.path("code").isTextual()&&"0".equals(body.path("code").textValue()))
                    ||present(root.path("error"))||present(body.path("error")))return Optional.empty();
            var units=root.path("units");
            if(!"m".equals(units.path("distance").asText())||!"s".equals(units.path("estimateTime").asText())||!"fen".equals(units.path("estimatePrice").asText()))return Optional.empty();
            var quoted=root.path("quotedAt");
            if(!quoted.isNumber())return Optional.empty();
            var quotedMillis=quoted.decimalValue().multiply(BigDecimal.valueOf(1000));
            if(quotedMillis.compareTo(BigDecimal.valueOf(now))>0)return Optional.empty();
            long quotedAt=quotedMillis.setScale(0,java.math.RoundingMode.FLOOR).longValueExact();
            // Never extend a provider timestamp from receipt time or tolerate future-dated snapshots.
            if(quotedAt<=0||quotedAt>now||quotedAt>Long.MAX_VALUE-300000||now>=quotedAt+300000)return Optional.empty();
            if(!root.path("stateVersion").isTextual()||!root.path("stateVersion").textValue().matches("[a-f0-9]{64}")||!text(data.path("estimateKey"),200))return Optional.empty();
            if(!nonnegative(data.path("distance"))||!nonnegative(data.path("estimateTime")))return Optional.empty();
            var trip=json.createObjectNode();
            for(String end:List.of("pickup","dropoff")){
                var point=root.path("trip").path(end);
                if(!point.path("selected").isBoolean()||!point.path("selected").booleanValue()||!text(point.path("poiid"),200)||!text(point.path("point_name"),500))return Optional.empty();
                var clean=trip.putObject(end);clean.put("poiid",point.path("poiid").textValue());clean.put("point_name",point.path("point_name").textValue());
            }
            var products=data.path("product");if(!products.isArray()||products.isEmpty()||products.size()>50)return Optional.empty();
            var options=json.createArrayNode();var seen=new HashSet<String>();
            for(var product:products){
                var rider=product.path("riderInfo");var prices=product.path("priceEstimate");
                if(!rider.path("rideType").isIntegralNumber()||!rider.path("rideType").canConvertToInt()||rider.path("rideType").intValue()<1||rider.path("rideType").intValue()>7||!prices.isArray()||prices.size()>50)return Optional.empty();
                for(var price:prices){
                    if(!text(price.path("priceEstimateKey"),200)||!nonnegative(price.path("estimatePrice"))||price.path("estimatePrice").decimalValue().stripTrailingZeros().scale()>0||!seen.add(price.path("priceEstimateKey").textValue()))return Optional.empty();
                    var option=options.addObject();option.put("rideType",rider.path("rideType").intValue());
                    if(text(rider.path("riderDesc"),200))option.put("riderDesc",rider.path("riderDesc").textValue());
                    // Deliberately omit estimate keys and stateVersion from transferable context.
                    option.set("amountFen",price.path("estimatePrice"));
                }
            }
            if(options.isEmpty())return Optional.empty();
            return Optional.of(new Value("ride_quote",Map.of("trip",json.writeValueAsString(trip),"options",json.writeValueAsString(options),
                    "distanceMeters",data.path("distance").asText(),"durationSeconds",data.path("estimateTime").asText(),
                    "quotedAtMillis",Long.toString(quotedAt)),quotedAt+300000));
        }catch(Exception invalid){return Optional.empty();}
    }
    private boolean integer(JsonNode node,long value){return node.isIntegralNumber()&&node.canConvertToLong()&&node.longValue()==value;}
    private boolean text(JsonNode node,int max){return node.isTextual()&&!node.textValue().isBlank()&&node.textValue().length()<=max;}
    private boolean present(JsonNode node){return !node.isMissingNode()&&!node.isNull();}
    private boolean nonnegative(JsonNode node){return node.isNumber()&&node.decimalValue().signum()>=0&&node.decimalValue().compareTo(BigDecimal.valueOf(1_000_000_000))<=0;}
}
