/*
 * Copyright The Cryostat Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.cryostat;

import static io.restassured.RestAssured.given;

import java.io.File;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openjdk.jmc.flightrecorder.rules.RuleRegistry;

@QuarkusTest
public class ReportResourceTest {

    @Test
    public void testHealthEndpoint() {
        given().when().get("/health").then().statusCode(204);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/profiling_sample.jfr",
                "/profiling_sample.jfr.gz",
            })
    public void testReportEndpoint(String filePath) throws URISyntaxException {
        File jfr = Paths.get(getClass().getResource(filePath).toURI()).toFile();
        String response =
                given().contentType("multipart/form-data")
                        .accept(ContentType.HTML)
                        .multiPart("file", jfr)
                        .when()
                        .post("/report")
                        .then()
                        .statusCode(200)
                        .contentType("text/html")
                        .body(Matchers.is(Matchers.not(Matchers.emptyOrNullString())))
                        .extract()
                        .asString();

        Document doc = Jsoup.parse(response, "UTF-8");

        Elements head = doc.getElementsByTag("head");
        Elements titles = head.first().getElementsByTag("title");
        Elements body = doc.getElementsByTag("body");

        MatcherAssert.assertThat("Expected one <head>", head.size(), Matchers.equalTo(1));
        MatcherAssert.assertThat("Expected one <title>", titles.size(), Matchers.equalTo(1));
        MatcherAssert.assertThat(
                titles.get(0).html(), Matchers.equalTo("Automated Analysis Result Overview"));
        MatcherAssert.assertThat("Expected one <body>", body.size(), Matchers.equalTo(1));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/profiling_sample.jfr",
                "/profiling_sample.jfr.gz",
            })
    public void testReportEndpointWithFilters(String filePath) throws URISyntaxException {
        File jfr = Paths.get(getClass().getResource(filePath).toURI()).toFile();
        String response =
                given().contentType("multipart/form-data")
                        .accept(ContentType.HTML)
                        .multiPart("file", jfr)
                        .formParam("filter", "LongGcPause,heap")
                        .when()
                        .post("/report")
                        .then()
                        .statusCode(200)
                        .contentType("text/html")
                        .body(Matchers.is(Matchers.not(Matchers.emptyOrNullString())))
                        .extract()
                        .asString();

        Document doc = Jsoup.parse(response, "UTF-8");

        Elements head = doc.getElementsByTag("head");
        Elements titles = head.first().getElementsByTag("title");
        Elements body = doc.getElementsByTag("body");
        Elements rules = doc.select("div.rule");
        Elements specificRule = doc.getElementsByAttributeValueMatching("name", "LongGcPause");

        MatcherAssert.assertThat("Expected one <head>", head.size(), Matchers.equalTo(1));
        MatcherAssert.assertThat("Expected one <title>", titles.size(), Matchers.equalTo(1));
        MatcherAssert.assertThat(
                titles.get(0).html(), Matchers.equalTo("Automated Analysis Result Overview"));
        MatcherAssert.assertThat("Expected one <body>", body.size(), Matchers.equalTo(1));
        MatcherAssert.assertThat(
                "Expect 8 heap rules, and 1 LongGcPause rule", rules.size(), Matchers.equalTo(9));
        MatcherAssert.assertThat(
                "Expect LongGcPause rule to be present",
                specificRule.attr("name"),
                Matchers.equalTo("LongGcPause"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/profiling_sample.jfr",
                "/profiling_sample.jfr.gz",
            })
    public void testReportEndpointWithInvalidFilters(String filePath) throws URISyntaxException {
        File jfr = Paths.get(getClass().getResource(filePath).toURI()).toFile();
        String response =
                given().contentType("multipart/form-data")
                        .accept(ContentType.HTML)
                        .multiPart("file", jfr)
                        .formParam("filter", "FakeRule")
                        .when()
                        .post("/report")
                        .then()
                        .statusCode(200)
                        .contentType("text/html")
                        .body(Matchers.is(Matchers.not(Matchers.emptyOrNullString())))
                        .extract()
                        .asString();

        Document doc = Jsoup.parse(response, "UTF-8");

        Elements head = doc.getElementsByTag("head");
        Elements titles = head.first().getElementsByTag("title");
        Elements body = doc.getElementsByTag("body");
        Elements rules = doc.select("div.rule");

        MatcherAssert.assertThat("Expected one <head>", head.size(), Matchers.equalTo(1));
        MatcherAssert.assertThat("Expected one <title>", titles.size(), Matchers.equalTo(1));
        MatcherAssert.assertThat(
                titles.get(0).html(), Matchers.equalTo("Automated Analysis Result Overview"));
        MatcherAssert.assertThat("Expected one <body>", body.size(), Matchers.equalTo(1));
        MatcherAssert.assertThat("Expect no rules", rules.size(), Matchers.equalTo(0));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/profiling_sample.jfr",
                "/profiling_sample.jfr.gz",
            })
    public void testReportEndpointForJSON(String filePath)
            throws URISyntaxException, JsonMappingException, JsonProcessingException {
        File jfr = Paths.get(getClass().getResource(filePath).toURI()).toFile();
        String response =
                given().contentType("multipart/form-data")
                        .accept(ContentType.JSON)
                        .multiPart("file", jfr)
                        .when()
                        .post("/report")
                        .then()
                        .statusCode(200)
                        .contentType("application/json")
                        .body(Matchers.is(Matchers.not(Matchers.emptyOrNullString())))
                        .extract()
                        .asString();

        ObjectMapper oMapper = new ObjectMapper();
        Map<String, RuleEvaluation> map =
                oMapper.readValue(response, new TypeReference<Map<String, RuleEvaluation>>() {});
        int numRules = RuleRegistry.getRules().size();

        MatcherAssert.assertThat(map, Matchers.notNullValue());
        MatcherAssert.assertThat(map, Matchers.aMapWithSize(numRules));
        for (var e : map.entrySet()) {
            MatcherAssert.assertThat(e, Matchers.notNullValue());
            MatcherAssert.assertThat(e.getValue(), Matchers.notNullValue());
            MatcherAssert.assertThat(
                    e.getValue().getName(), Matchers.not(Matchers.emptyOrNullString()));
            MatcherAssert.assertThat(
                    e.getValue().getTopic(), Matchers.not(Matchers.emptyOrNullString()));
            MatcherAssert.assertThat(
                    e.getValue().getScore(),
                    Matchers.anyOf(
                            Matchers.equalTo(-1d),
                            Matchers.equalTo(-2d),
                            Matchers.equalTo(-3d),
                            Matchers.both(Matchers.lessThanOrEqualTo(100d))
                                    .and(Matchers.greaterThanOrEqualTo(0d))));
        }
    }

    private static class RuleEvaluation {
        private double score;
        private String name;
        private String topic;
        private String description;

        private RuleEvaluation() {}

        @JsonProperty("score")
        public double getScore() {
            return score;
        }

        @JsonProperty("name")
        public String getName() {
            return name;
        }

        @JsonProperty("topic")
        public String getTopic() {
            return topic;
        }

        @JsonProperty("description")
        public String getDescription() {
            return description;
        }
    }
}
