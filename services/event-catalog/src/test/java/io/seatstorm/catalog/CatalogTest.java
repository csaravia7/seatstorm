package io.seatstorm.catalog;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.seatstorm.contracts.EventMetas;
import io.seatstorm.contracts.ResetCompleted;
import io.seatstorm.contracts.ResetRequested;
import io.seatstorm.contracts.SaleClosed;
import io.seatstorm.contracts.SaleOpened;
import io.seatstorm.contracts.Sections;
import io.seatstorm.contracts.Topics;
import io.seatstorm.platform.outbox.Outbox;
import io.smallrye.reactive.messaging.kafka.companion.KafkaCompanion;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CatalogTest {

    static final String TOKEN = "test-admin-token";

    @Inject TestSupport support;
    @Inject Outbox outbox;
    @Inject EntityManager em;

    private long events(String topic, Class<?> type) {
        return support.count("select count(*) from outbox where topic = '" + topic + "' and event_type = '"
                + type.getSimpleName() + "'");
    }

    @Test
    @Order(1)
    void concertReturnsSeedWithTwelveSections() {
        var r = given().get("/api/concert").then().statusCode(200)
                .body("concertId", is("neon-tide-2026"))
                .body("name", is("Neon Tide · Arena Norte"))
                .body("venue", notNullValue())
                .body("saleStatus", is("CLOSED"))
                .body("sections", hasSize(12))
                .extract();
        List<Map<String, Object>> sections = r.path("sections");
        for (Sections.Section s : Sections.ALL) {
            Map<String, Object> got = sections.get(s.index());
            assertEquals(s.id(), got.get("sectionId"));
            assertEquals(s.index(), got.get("index"));
            assertEquals(s.label(), got.get("label"));
            assertEquals(s.priceCents(), got.get("priceCents"));
            assertEquals(3, got.get("rows"));
            assertEquals(10, got.get("seatsPerRow"));
        }
        assertEquals(1, support.count("select count(*) from concert"));
        assertEquals(12, support.count("select count(*) from section"));
    }

    @Test
    @Order(2)
    void adminRequiresToken() {
        given().post("/admin/sale/open").then().statusCode(401).body("code", is("BAD_ADMIN_TOKEN"));
        given().header("X-Admin-Token", "nope").post("/admin/reset").then().statusCode(401)
                .body("code", is("BAD_ADMIN_TOKEN"));
        given().get("/admin/reset/x").then().statusCode(401);
        assertEquals(0, events(Topics.SALE_EVENTS, SaleOpened.class));
    }

    @Test
    void renameConcertValidatesAndPersists() {
        String original = given().get("/api/concert").then().extract().path("name");
        try {
            given().contentType("application/json").body("{\"name\":\"x\"}").put("/admin/concert").then().statusCode(401);
            given().header("X-Admin-Token", TOKEN).contentType("application/json").body("{\"name\":\"   \"}")
                    .put("/admin/concert").then().statusCode(400).body("code", is("VALIDATION_ERROR"));
            given().header("X-Admin-Token", TOKEN).contentType("application/json")
                    .body("{\"name\":\"" + "a".repeat(81) + "\"}").put("/admin/concert").then().statusCode(400);
            given().header("X-Admin-Token", TOKEN).contentType("application/json")
                    .body("{\"name\":\"  Mi   Evento  \"}").put("/admin/concert").then().statusCode(200)
                    .body("name", is("Mi Evento"));
            given().get("/api/concert").then().body("name", is("Mi Evento"));
        } finally {
            given().header("X-Admin-Token", TOKEN).contentType("application/json")
                    .body("{\"name\":\"" + original + "\"}").put("/admin/concert");
        }
    }

    @Test
    @Order(3)
    void openAndCloseSaleArePublishedOnceAndIdempotent() {
        try (KafkaCompanion kc = new KafkaCompanion(TestSupport.bootstrap())) {
            given().header("X-Admin-Token", TOKEN).post("/admin/sale/open").then().statusCode(202);
            given().header("X-Admin-Token", TOKEN).post("/admin/sale/open").then().statusCode(202);
            given().get("/api/concert").then().body("saleStatus", is("OPEN"));
            assertEquals(1, events(Topics.SALE_EVENTS, SaleOpened.class));

            given().header("X-Admin-Token", TOKEN).post("/admin/sale/close").then().statusCode(202);
            given().header("X-Admin-Token", TOKEN).post("/admin/sale/close").then().statusCode(202);
            given().get("/api/concert").then().body("saleStatus", is("CLOSED"));
            assertEquals(1, events(Topics.SALE_EVENTS, SaleClosed.class));

            support.awaitRelayed();
            var recs = TestSupport.consume(kc, Topics.SALE_EVENTS, 2);
            assertEquals(2, recs.size());
            assertTrue(recs.get(0).value() instanceof SaleOpened);
            assertTrue(recs.get(1).value() instanceof SaleClosed);
            assertEquals("neon-tide-2026", recs.get(0).key());
            assertEquals("event-catalog", ((SaleOpened) recs.get(0).value()).getMeta().getProducer());
        }
    }

    @Test
    @Order(4)
    void resetLifecycle() {
        long closedBefore = events(Topics.SALE_EVENTS, SaleClosed.class);
        given().header("X-Admin-Token", TOKEN).post("/admin/sale/open").then().statusCode(202);

        String resetId = given().header("X-Admin-Token", TOKEN).post("/admin/reset").then().statusCode(202)
                .body("resetId", notNullValue()).extract().path("resetId");

        given().header("X-Admin-Token", TOKEN).get("/admin/reset/" + resetId).then().statusCode(200)
                .body("done", is(false))
                .body("pending", containsInAnyOrder("order-service", "seat-inventory", "payment-sim", "ticketing",
                        "seatmap-query"));
        given().header("X-Admin-Token", TOKEN).get("/admin/reset/unknown").then().statusCode(404)
                .body("code", is("NOT_FOUND"));

        // the catalog's own handler: sale CLOSED + SaleClosed (consumes its own ResetRequested)
        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            given().get("/api/concert").then().body("saleStatus", is("CLOSED"));
            assertEquals(closedBefore + 1, events(Topics.SALE_EVENTS, SaleClosed.class));
        });

        // other services answer one by one
        for (String svc : List.of("order-service", "seat-inventory", "payment-sim", "ticketing")) {
            complete(resetId, svc);
            Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> given()
                    .header("X-Admin-Token", TOKEN).get("/admin/reset/" + resetId).then()
                    .body("pending", not(hasItem(svc))));
            given().header("X-Admin-Token", TOKEN).get("/admin/reset/" + resetId).then().body("done", is(false));
        }
        complete(resetId, "seatmap-query");
        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> given()
                .header("X-Admin-Token", TOKEN).get("/admin/reset/" + resetId).then()
                .body("done", is(true)).body("pending", hasSize(0)));

        // ResetRequested reached demo.control
        support.awaitRelayed();
        try (KafkaCompanion kc = new KafkaCompanion(TestSupport.bootstrap())) {
            long n = support.count("select count(*) from outbox where topic = 'demo.control'");
            List<ConsumerRecord<String, SpecificRecord>> recs = TestSupport.consume(kc, Topics.DEMO_CONTROL, (int) n);
            assertTrue(recs.stream().anyMatch(r -> r.value() instanceof ResetRequested q
                    && q.getResetId().equals(resetId) && resetId.equals(r.key())));
        }
    }

    private void complete(String resetId, String service) {
        var ev = ResetCompleted.newBuilder().setMeta(EventMetas.of(service, resetId)).setResetId(resetId)
                .setService(service).build();
        QuarkusTransaction.requiringNew().run(() -> outbox.add(Topics.DEMO_CONTROL, resetId, null, ev));
    }
}
