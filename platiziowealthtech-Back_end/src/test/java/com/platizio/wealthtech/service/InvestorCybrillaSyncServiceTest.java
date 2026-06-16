package com.platizio.wealthtech.service;



import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.ArgumentMatchers.any;

import static org.mockito.ArgumentMatchers.eq;

import static org.mockito.Mockito.never;

import static org.mockito.Mockito.verify;

import static org.mockito.Mockito.when;



import com.fasterxml.jackson.databind.ObjectMapper;

import com.platizio.wealthtech.domain.Investor;

import com.platizio.wealthtech.integration.CybrillaClient;

import com.platizio.wealthtech.repository.InvestorRepository;

import java.util.Optional;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import org.mockito.ArgumentCaptor;

import org.springframework.test.util.ReflectionTestUtils;



class InvestorCybrillaSyncServiceTest {



    private static final ObjectMapper MAPPER = new ObjectMapper();



    @Test

    void explicitSyncRestoresSoftDeletedInvestorWhenProfileStillExistsInCybrilla() throws Exception {

        InvestorRepository repository = org.mockito.Mockito.mock(InvestorRepository.class);

        CybrillaClient cybrillaClient = org.mockito.Mockito.mock(CybrillaClient.class);

        InvestorCybrillaSyncService service = new InvestorCybrillaSyncService(cybrillaClient, repository, 0, "cybrilla", null);



        UUID distributorId = UUID.randomUUID();

        Investor deleted = new Investor();

        deleted.setDistributorId(distributorId);

        deleted.setPan("AAAPA3751A");

        deleted.setFullName("Old Name");

        deleted.setEmail("old@example.com");

        deleted.setMobileNumber("9999999999");

        deleted.setIsDeleted(true);

        ReflectionTestUtils.setField(deleted, "id", UUID.randomUUID());



        when(cybrillaClient.listInvestorProfiles(null, "individual")).thenReturn(MAPPER.readTree("""

                {

                  "object": "list",

                  "data": [

                    {

                      "id": "invp_test123",

                      "pan": "AAAPA3751A",

                      "name": "Synced Name",

                      "date_of_birth": "1990-01-01"

                    }

                  ]

                }

                """));

        when(repository.findIncludingDeletedByCybrillaInvestorId("invp_test123")).thenReturn(Optional.empty());

        when(repository.findIncludingDeletedByPanAndDistributor("AAAPA3751A", distributorId)).thenReturn(Optional.of(deleted));

        when(repository.save(any(Investor.class))).thenAnswer(invocation -> invocation.getArgument(0));



        InvestorCybrillaSyncService.InvestorSyncResult result = service.syncProfilesForDistributor(distributorId, true, false);



        ArgumentCaptor<Investor> saved = ArgumentCaptor.forClass(Investor.class);

        verify(repository).save(saved.capture());

        assertThat(result.restored()).isEqualTo(1);

        assertThat(result.imported()).isZero();

        assertThat(saved.getValue().getIsDeleted()).isFalse();

        assertThat(saved.getValue().getCybrillaInvestorId()).isEqualTo("invp_test123");

        assertThat(saved.getValue().getFullName()).isEqualTo("Synced Name");

    }



    @Test

    void passiveSyncLeavesArchivedInvestorHidden() throws Exception {

        InvestorRepository repository = org.mockito.Mockito.mock(InvestorRepository.class);

        CybrillaClient cybrillaClient = org.mockito.Mockito.mock(CybrillaClient.class);

        InvestorCybrillaSyncService service = new InvestorCybrillaSyncService(cybrillaClient, repository, 0, "cybrilla", null);



        UUID distributorId = UUID.randomUUID();

        Investor deleted = new Investor();

        deleted.setDistributorId(distributorId);

        deleted.setPan("AAAPA3751A");

        deleted.setCybrillaInvestorId("invp_test123");

        deleted.setIsDeleted(true);



        when(cybrillaClient.listInvestorProfiles(null, "individual")).thenReturn(MAPPER.readTree("""

                {

                  "object": "list",

                  "data": [

                    {

                      "id": "invp_test123",

                      "pan": "AAAPA3751A",

                      "name": "Synced Name",

                      "date_of_birth": "1990-01-01"

                    }

                  ]

                }

                """));

        when(repository.findIncludingDeletedByCybrillaInvestorId("invp_test123")).thenReturn(Optional.of(deleted));



        InvestorCybrillaSyncService.InvestorSyncResult result = service.syncProfilesForDistributor(distributorId, false, false);



        verify(repository, never()).save(any(Investor.class));

        assertThat(result.restored()).isZero();

        assertThat(result.skippedArchived()).isEqualTo(1);

    }



    @Test

    void passiveSyncDoesNotImportUnlinkedProfiles() throws Exception {

        InvestorRepository repository = org.mockito.Mockito.mock(InvestorRepository.class);

        CybrillaClient cybrillaClient = org.mockito.Mockito.mock(CybrillaClient.class);

        InvestorCybrillaSyncService service = new InvestorCybrillaSyncService(cybrillaClient, repository, 0, "cybrilla", null);



        when(cybrillaClient.listInvestorProfiles(null, "individual")).thenReturn(MAPPER.readTree("""

                {

                  "object": "list",

                  "data": [

                    {

                      "id": "invp_new_profile",

                      "pan": "AAAPA3751A",

                      "name": "New Profile",

                      "date_of_birth": "1990-01-01"

                    }

                  ]

                }

                """));

        when(repository.findIncludingDeletedByCybrillaInvestorId("invp_new_profile")).thenReturn(Optional.empty());

        when(repository.findIncludingDeletedByPanAndDistributor(eq("AAAPA3751A"), any())).thenReturn(Optional.empty());



        InvestorCybrillaSyncService.InvestorSyncResult result = service.syncProfilesForDistributor(UUID.randomUUID(), false, false);



        verify(repository, never()).save(any(Investor.class));

        assertThat(result.imported()).isZero();

        assertThat(result.skippedUnlinked()).isEqualTo(1);

    }



    @Test

    void importNewSyncImportsUnlinkedProfiles() throws Exception {

        InvestorRepository repository = org.mockito.Mockito.mock(InvestorRepository.class);

        CybrillaClient cybrillaClient = org.mockito.Mockito.mock(CybrillaClient.class);

        InvestorCybrillaSyncService service = new InvestorCybrillaSyncService(cybrillaClient, repository, 0, "cybrilla", null);



        UUID distributorId = UUID.randomUUID();

        when(cybrillaClient.listInvestorProfiles(null, "individual")).thenReturn(MAPPER.readTree("""

                {

                  "object": "list",

                  "data": [

                    {

                      "id": "invp_new_profile",

                      "pan": "AAAPA3751A",

                      "name": "New Profile",

                      "date_of_birth": "1990-01-01"

                    }

                  ]

                }

                """));

        when(repository.findIncludingDeletedByCybrillaInvestorId("invp_new_profile")).thenReturn(Optional.empty());

        when(repository.findIncludingDeletedByPanAndDistributor(eq("AAAPA3751A"), any())).thenReturn(Optional.empty());

        when(repository.findIncludingDeletedByPan("AAAPA3751A")).thenReturn(Optional.empty());

        when(repository.save(any(Investor.class))).thenAnswer(invocation -> {

            Investor investor = invocation.getArgument(0);

            ReflectionTestUtils.setField(investor, "id", UUID.randomUUID());

            return investor;

        });



        InvestorCybrillaSyncService.InvestorSyncResult result = service.syncProfilesForDistributor(distributorId, true, true);



        verify(repository, org.mockito.Mockito.atLeastOnce()).save(any(Investor.class));

        assertThat(result.imported()).isEqualTo(1);

    }

}


