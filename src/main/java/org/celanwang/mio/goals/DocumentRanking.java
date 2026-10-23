package org.celanwang.mio.goals;

import java.util.*;

/** Rank fusion keeps lexical and semantic recall independent of score scales. */
public final class DocumentRanking {
    private DocumentRanking() {}

    public static List<Map<String,Object>> fuse(List<Map<String,Object>> lexical, List<Map<String,Object>> semantic) {
        var hits = new LinkedHashMap<String,Map<String,Object>>();
        var scores = new HashMap<String,Double>();
        for (var list : List.of(lexical,semantic)) {
            for (int rank=0; rank<list.size(); rank++) {
                var hit=list.get(rank);
                String key=hit.get("id")+":"+hit.get("revision")+":"+hit.get("start_offset");
                var row=hits.computeIfAbsent(key,k->new LinkedHashMap<>(hit));
                var sources=new LinkedHashSet<>(Arrays.asList(String.valueOf(row.get("source")).split("\\+")));
                sources.add(String.valueOf(hit.get("source")));
                row.put("source",String.join("+",sources));
                scores.merge(key,1.0/(60+rank+1),Double::sum);
                row.put("rankScore",scores.get(key));
                row.put("citation",Map.of("documentId",hit.get("id"),"revision",hit.get("revision"),"startOffset",hit.get("start_offset"),"endOffset",hit.get("end_offset")));
            }
        }
        var ordered=new ArrayList<>(hits.values());
        ordered.sort(Comparator.<Map<String,Object>>comparingDouble(r->((Number)r.get("rankScore")).doubleValue()).reversed()
                .thenComparing(r->String.valueOf(r.get("id"))).thenComparingInt(r->((Number)r.get("start_offset")).intValue()));
        var counts=new HashMap<Object,Integer>();
        return ordered.stream().filter(r->counts.merge(r.get("id"),1,Integer::sum)<=3).limit(20).toList();
    }
}
