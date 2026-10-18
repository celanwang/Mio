package org.celanwang.mio.evaluation;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Five fresh worlds per case. Reliability numbers from these runs are fixture checks, not LLM evidence. */
class FixtureBaselineTest {
    @Test void exportVersionedFixtureObservations() throws Exception {
        var mapper = new ObjectMapper();
        var corpus = Path.of("eval/datasets/tasks.json");
        var cases = mapper.readTree(corpus.toFile()).path("cases");
        var output = new ArrayList<Map<String,Object>>();
        for (JsonNode task : cases) {
            String skill = task.path("skill").asText();
            for (int sample = 0; sample < 5; sample++) {
                try (var h = new AgentHarness()) {
                    if (skill.equals("tencent-ride")) h.addRide();
                    else if (skill.equals("mcd-ordering")) {
                        h.add("query-meals", true); h.add("calculate-price", true); h.add("create-order", false);
                    } else { h.add("eval_search_weibo", true); h.add("eval_publish_weibo", false); }
                    for (JsonNode step : task.path("steps")) {
                        Map<String,Object> input = mapper.convertValue(step.path("input"), new com.fasterxml.jackson.core.type.TypeReference<>() {});
                        h.call(step.path("tool").asText(), input);
                    }
                    h.say("SYNTHETIC done");
                    var response = h.start();
                    if (!task.path("approval").isNull()) {
                        assertThat(response.getPendingConfirmations()).as(task.path("id").asText()).hasSize(1);
                        h.decide(response, task.path("approval").asBoolean());
                    } else assertThat(response.getPendingConfirmations()).isEmpty();
                    assertThat(mapper.<JsonNode>valueToTree(h.world.state)).as(task.path("id").asText()).isEqualTo(task.path("final_state"));
                    assertThat(mapper.<JsonNode>valueToTree(h.world.actions)).isEqualTo(task.path("actions"));
                    Map<String,Object> row = new LinkedHashMap<>();
                    row.put("task_id", task.path("id").asText()); row.put("sample_id", "fixture-" + sample);
                    row.put("source", "scripted"); row.put("model", "scripted-fixture-v1"); row.put("route", "fixture");
                    row.put("temperature", 0); row.put("timestamp", OffsetDateTime.now().toString());
                    row.put("dataset_sha256", sha(corpus)); row.put("skill_sha256", skillSha(skill));
                    row.put("session_id", h.session);
                    row.put("evaluation_target", "permission-fixture");
                    row.put("prompt_sha256", sha(Path.of("src/test/java/org/celanwang/mio/evaluation/AgentHarness.java")));
                    row.put("final_state", new LinkedHashMap<>(h.world.state)); row.put("actions", List.copyOf(h.world.actions));
                    row.put("events", h.traces.events(h.session)); output.add(row);
                }
            }
        }
        Path directory = Path.of("target/evaluation"); Files.createDirectories(directory);
        StringBuilder jsonl = new StringBuilder();
        for (var row : output) jsonl.append(mapper.writeValueAsString(row)).append('\n');
        Files.writeString(directory.resolve("fixture-runs.jsonl"), jsonl);
    }
    static String skillSha(String skill) throws Exception {
        Path root = Path.of("skills", skill);
        var digest = MessageDigest.getInstance("SHA-256");
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(p -> !p.toString().contains("__pycache__") && !p.toString().endsWith(".pyc") && !p.getFileName().toString().equals(".DS_Store"))
                    .sorted(Comparator.comparing(p -> root.relativize(p).toString())).toList()) {
                digest.update(root.relativize(file).toString().replace('\\','/').getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update((byte)0); digest.update(Files.readAllBytes(file)); digest.update((byte)0);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    static String sha(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
