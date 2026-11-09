package io.seatstorm.platform;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class WebTest {
    @Test
    void adminRequiresToken() {
        given().get("/admin/ping").then().statusCode(401)
                .body("code", is("BAD_ADMIN_TOKEN")).body("message", notNullValue()).body("details", notNullValue());
        given().header("X-Admin-Token", "nope").get("/admin/ping").then().statusCode(401)
                .body("code", is("BAD_ADMIN_TOKEN"));
        given().header("X-Admin-Token", "test-admin-token").get("/admin/ping").then().statusCode(200)
                .body(is("pong"));
    }

    @Test
    void nonAdminPathsAreOpen() {
        given().get("/api/taken").then().statusCode(409)
                .body("code", is("SEAT_TAKEN")).body("message", is("Asiento ocupado"))
                .body("details.seatId", is("PLATEA-A:A01"));
    }

    @Test
    void unexpectedErrorsDoNotLeakInternals() {
        given().get("/api/boom").then().statusCode(500)
                .body("code", is("INTERNAL_ERROR")).body("message", not(containsString("secret")));
    }

    @Test
    void notFoundUsesModel() {
        given().get("/api/missing").then().statusCode(404).body("code", is("NOT_FOUND"));
    }
}
