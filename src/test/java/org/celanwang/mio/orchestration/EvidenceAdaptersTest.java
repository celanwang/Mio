package org.celanwang.mio.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class EvidenceAdaptersTest {
    @TempDir Path directory;
    final ObjectMapper json=new ObjectMapper();
    Map<String,Object> read(){return Map.ofEntries(Map.entry("id","meal-quote"),Map.entry("enabled",true),Map.entry("capabilityId","meal"),Map.entry("tool","calculate-price"),Map.entry("mode","READ"),Map.entry("description","fixture contract"),Map.entry("factKey","meal.quote"),Map.entry("factType","meal_quote"),Map.entry("ttlSeconds",60),Map.entry("conditions",Map.of("/ok",true,"/unit","fen")),Map.entry("fields",Map.of("amountFen",Map.of("pointer","/amount","type","NUMBER","unit","分","label","应付金额"))),Map.entry("invalidatingTools",List.of("create-order")));}
    @Test void explicitReadAndWriteContractsFailClosedOnMalformedOrAmbiguousResponses()throws Exception{
        Files.writeString(directory.resolve("read.json"),json.writeValueAsString(read()));
        Files.writeString(directory.resolve("write.json"),json.writeValueAsString(Map.of("id","meal-created","enabled",true,"capabilityId","meal","tool","create-order","mode","WRITE","description","fixture create","conditions",Map.of("/ok",true),"resourceIdPointer","/id")));
        var adapters=new EvidenceAdapters(directory,json);var fact=adapters.extractors().get(0);assertThat(fact.extract("calculate-price",Map.of(),"{\"ok\":true,\"unit\":\"fen\",\"amount\":1234}",1000).orElseThrow().value()).containsEntry("amountFen","1234");
        for(String raw:List.of("{\"ok\":\"true\",\"unit\":\"fen\",\"amount\":1234}","{\"ok\":true,\"unit\":\"yuan\",\"amount\":1234}","{\"ok\":true,\"unit\":\"fen\",\"amount\":\"1234\"}","{\"ok\":false,\"ok\":true,\"unit\":\"fen\",\"amount\":1234}"))assertThat(fact.extract("calculate-price",Map.of(),raw,1000)).isEmpty();
        assertThat(fact.invalidatedKey("create-order",Map.of())).contains("meal.quote");var verifier=adapters.verifiers().get(0);assertThat(verifier.verify("{\"ok\":true,\"id\":\"REAL-O1\"}").outcome()).isEqualTo(OperationOutcome.CONFIRMED_APPLIED);assertThat(verifier.verify("{\"ok\":true,\"id\":9007199254740995}").outcome()).isEqualTo(OperationOutcome.UNKNOWN);
        var capability=new AgentCapability("meal","meal","meal","meal","v1",List.of());var decorated=adapters.decorate(List.of(new DomainDefinition(capability,"prompt",List::of))).get(0).capability();assertThat(decorated.outputContracts()).extracting(CapabilityOutputContract::id).contains("meal-quote","resource-reference");assertThat(decorated.executionVersion()).isNotEqualTo(capability.executionVersion());
    }
    @Test void unknownCapabilitiesAndDuplicateAdaptersCannotSilentlyAttach()throws Exception{Files.writeString(directory.resolve("a.json"),json.writeValueAsString(read()));var adapters=new EvidenceAdapters(directory,json);assertThatThrownBy(()->adapters.decorate(List.of())).hasMessageContaining("未知能力");Files.writeString(directory.resolve("b.json"),json.writeValueAsString(read()));assertThatThrownBy(()->new EvidenceAdapters(directory,json)).hasMessageContaining("重复");}
}
