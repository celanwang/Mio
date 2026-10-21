package org.celanwang.mio.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Parses the existing Python bridge envelope. Acceptance proves creation, not dispatch/payment. */
public final class TencentOrderReceiptVerifier implements OperationVerifier {
    private final ObjectMapper json=new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    @Override public boolean supports(String tool){return "tencent_ride_create_order".equals(tool);}
    @Override public Verification verify(String raw){
        var unknown=new Verification(OperationOutcome.UNKNOWN,"","tencent-create-receipt-v1");
        try {
            var root=json.readTree(raw);var result=root.path("output").path("result");var body=result.path("body");
            if(!root.path("exitCode").isIntegralNumber()||!"0".equals(root.path("exitCode").asText())
                    ||!result.path("status").isIntegralNumber()||!"200".equals(result.path("status").asText())
                    ||!(body.path("code").isIntegralNumber()&&"0".equals(body.path("code").asText())
                       ||body.path("code").isTextual()&&"0".equals(body.path("code").asText())))return unknown;
            var id=body.path("data").path("orderId");
            if(!id.isTextual()||!id.asText().matches("[A-Za-z0-9_-]{1,128}")||"null".equals(id.asText()))return unknown;
            return new Verification(OperationOutcome.CONFIRMED_APPLIED,id.asText(),"tencent-create-receipt-v1");
        }catch(Exception malformed){return unknown;}
    }
}
