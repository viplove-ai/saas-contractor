package in.nirman;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 2 exit criterion: Swagger lists every foundation endpoint. Checks the generated
 * OpenAPI document rather than the UI — the UI is just a renderer of this document.
 */
class OpenApiCoverageTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("the OpenAPI document covers every foundation endpoint")
    void foundationEndpointsAreDocumented() throws Exception {
        String doc = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(doc).contains(
                "/api/v1/auth/login",
                "/api/v1/auth/refresh",
                "/api/v1/auth/logout",
                "/api/v1/auth/password/change",
                "/api/v1/auth/me",
                "/api/v1/users",
                "/api/v1/users/{id}",
                "/api/v1/users/{id}/status",
                "/api/v1/users/{id}/roles",
                "/api/v1/users/{id}/sites",
                "/api/v1/roles",
                "/api/v1/permissions",
                "/api/v1/projects",
                "/api/v1/projects/{id}",
                "/api/v1/projects/{id}/summary",
                "/api/v1/sites",
                "/api/v1/sites/{id}",
                "/api/v1/sites/{id}/stores",
                "/api/v1/units",
                "/api/v1/skill-categories",
                "/api/v1/material-categories",
                "/api/v1/vendors",
                "/api/v1/vendors/{id}",
                "/api/v1/staff",
                "/api/v1/staff/{userId}",
                "/api/v1/materials",
                "/api/v1/materials/{id}",
                "/api/v1/materials/{id}/conversions",
                "/api/v1/expense-categories",
                "/api/v1/attachments",
                "/api/v1/attachments/{id}/url",
                "/api/v1/attachments/{id}");
    }

    @Test
    @DisplayName("the OpenAPI document covers every labour endpoint")
    void labourEndpointsAreDocumented() throws Exception {
        String doc = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(doc).contains(
                "/api/v1/workers",
                "/api/v1/workers/{id}",
                "/api/v1/workers/{id}/wage-rates",
                "/api/v1/workers/{id}/allocations",
                "/api/v1/workers/{id}/settlement",
                "/api/v1/attendance",
                "/api/v1/attendance/roster",
                "/api/v1/attendance/bulk",
                "/api/v1/attendance/{id}",
                "/api/v1/attendance/submit",
                "/api/v1/attendance/verify",
                "/api/v1/attendance/lock",
                "/api/v1/worker-advances",
                "/api/v1/worker-advances/{id}/decision");
    }

    @Test
    @DisplayName("the OpenAPI document covers every inventory endpoint")
    void inventoryEndpointsAreDocumented() throws Exception {
        String doc = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(doc).contains(
                "/api/v1/inventory/goods-receipts",
                "/api/v1/inventory/goods-receipts/{id}",
                "/api/v1/inventory/goods-receipts/{id}/verify",
                "/api/v1/inventory/issues",
                "/api/v1/inventory/issues/{id}",
                "/api/v1/inventory/issues/{id}/approve",
                "/api/v1/inventory/transfers",
                "/api/v1/inventory/transfers/{id}",
                "/api/v1/inventory/transfers/{id}/dispatch",
                "/api/v1/inventory/transfers/{id}/receive",
                "/api/v1/inventory/counts",
                "/api/v1/inventory/counts/{id}",
                "/api/v1/inventory/counts/{id}/decision",
                "/api/v1/inventory/opening-stock",
                "/api/v1/inventory/wastage",
                "/api/v1/inventory/adjustments",
                "/api/v1/inventory/stock",
                "/api/v1/inventory/ledger",
                "/api/v1/inventory/equipment",
                "/api/v1/inventory/equipment/{id}",
                "/api/v1/inventory/equipment/{id}/decision",
                "/api/v1/boq-items",
                "/api/v1/boq-items/{id}",
                "/api/v1/material-estimates",
                "/api/v1/material-estimates/derive",
                "/api/v1/material-estimates/variance");
    }

    @Test
    @DisplayName("the OpenAPI document covers every expense and cash endpoint")
    void expenseEndpointsAreDocumented() throws Exception {
        String doc = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(doc).contains(
                "/api/v1/expenses",
                "/api/v1/expenses/{id}",
                "/api/v1/expenses/{id}/submit",
                "/api/v1/expenses/{id}/approve",
                "/api/v1/expenses/{id}/void",
                "/api/v1/expenses/{id}/attachments",
                "/api/v1/payments",
                "/api/v1/vendors/balances",
                "/api/v1/advances",
                "/api/v1/advances/{id}",
                "/api/v1/advances/balances",
                "/api/v1/advances/{id}/settlements",
                "/api/v1/settlements/{id}/decision",
                "/api/v1/approvals/pending",
                "/api/v1/approvals/{id}/action",
                "/api/v1/approvals/history",
                "/api/v1/approvals/rules");
    }

    @Test
    @DisplayName("the OpenAPI document covers every report the API spec names for phases 3 to 5")
    void reportEndpointsAreDocumented() throws Exception {
        String doc = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(doc).contains(
                "/api/v1/reports/attendance-register",
                "/api/v1/reports/wage-summary",
                "/api/v1/reports/stock-position",
                "/api/v1/reports/material-consumption",
                "/api/v1/reports/low-stock",
                "/api/v1/reports/transfer-register",
                "/api/v1/reports/wastage",
                "/api/v1/reports/expense-register",
                "/api/v1/reports/payable-ageing",
                "/api/v1/reports/advance-balances");
    }
}
