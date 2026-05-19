package com.platizio.wealthtech.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

class SearchQueryOptimizationTest {

    @Test
    void investorSearchQueriesUseIlikeWithoutLowerWrappingColumns() throws NoSuchMethodException {
        assertOptimized(InvestorRepository.class.getMethod("search", String.class, Pageable.class));
        assertOptimized(InvestorRepository.class.getMethod(
                "searchByDistributor", UUID.class, String.class, Pageable.class));
        assertOptimized(InvestorRepository.class.getMethod(
                "searchByDistributorIds", List.class, String.class, Pageable.class));
    }

    @Test
    void distributorSearchQueriesUseIlikeWithoutLowerWrappingColumns() throws NoSuchMethodException {
        assertOptimized(DistributorRepository.class.getMethod("search", String.class, Pageable.class));
        assertOptimized(DistributorRepository.class.getMethod(
                "searchByMasterDistributor", UUID.class, String.class, Pageable.class));
    }

    @Test
    void migrationAddsPgTrgmIndexesForSearchedColumns() throws Exception {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V16__add_pg_trgm_search_indexes.sql"));

        assertThat(migration).contains("CREATE EXTENSION IF NOT EXISTS pg_trgm");
        assertThat(migration).contains("idx_investor_full_name_trgm");
        assertThat(migration).contains("idx_investor_email_trgm");
        assertThat(migration).contains("idx_investor_mobile_number_trgm");
        assertThat(migration).contains("idx_investor_pan_trgm");
        assertThat(migration).contains("idx_distributor_full_name_trgm");
        assertThat(migration).contains("idx_distributor_email_trgm");
        assertThat(migration).contains("idx_distributor_mobile_number_trgm");
        assertThat(migration).contains("idx_distributor_arn_number_trgm");
        assertThat(migration).contains("idx_distributor_e_uin_number_trgm");
    }

    private void assertOptimized(Method method) {
        String query = method.getAnnotation(Query.class).value().toLowerCase();

        assertThat(method.getAnnotation(Query.class).nativeQuery()).isTrue();
        assertThat(query).contains(" ilike concat('%', :query, '%')");
        assertThat(query).doesNotContain("lower(");
    }
}
