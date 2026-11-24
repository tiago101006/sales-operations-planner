package pt.tiagodias.sop.monitoring;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;
import static pt.tiagodias.sop.monitoring.InsightDtos.*;

/** Cached AI ordering is reused; the current concise view is composed from its immutable evidence. */
final class InsightReportCache {
    private InsightReportCache() {}

    static String encode(Analise analysis,List<String> order,JsonMapper json) {
        // Internal metadata is not part of the public Analise DTO.
        return json.writeValueAsString(Map.of("titulo",analysis.titulo(),"secoes",analysis.secoes(),"prioridades",order));
    }

    static Analise read(Snapshot snapshot,String stored,JsonMapper json) {
        var tree=json.readTree(stored);
        var order=new ArrayList<String>();
        if(tree.path("prioridades").isArray()) {
            for(var id:tree.get("prioridades")) {
                if(!id.isTextual())throw new IllegalArgumentException("Invalid cached recommendation order.");
                order.add(id.asText());
            }
        } else {
            // v6 reports stored the complete ranking as numbered, application-authored actions.
            // Recover exact titles, never model prose or current product names, without inference.
            var catalog=GroundedInsightReport.recommendations(snapshot);
            var sections=tree.path("secoes");
            if(!sections.isArray()||sections.isEmpty())throw new IllegalArgumentException("Invalid cached report.");
            int number=1;
            for(var paragraph:sections.get(sections.size()-1).path("paragrafos")) {
                String text=paragraph.asText();
                String prefix=number+". ";
                if(!text.startsWith(prefix))continue;
                var matched=catalog.stream().filter(r->text.startsWith(prefix+r.titulo()+". ")).findFirst()
                        .orElseThrow(()->new IllegalArgumentException("Unknown cached recommendation."));
                order.add(matched.id());number++;
            }
        }
        return GroundedInsightReport.compose(snapshot,order);
    }
}
