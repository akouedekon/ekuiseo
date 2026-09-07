package bj.ekuiseo.api.service.geo;

import bj.ekuiseo.api.domain.GeoPlace;
import bj.ekuiseo.api.domain.enums.GeoPlaceKind;
import bj.ekuiseo.api.dto.geo.GeoPlaceResponse;
import bj.ekuiseo.api.mapper.GeoPlaceMapper;
import bj.ekuiseo.api.repository.GeoPlaceRepository;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Point n.11 de l audit (constats F421/F422) : repli tolerant aux fautes, ville de rattachement, referentiel complet. */
class GeocodingServiceTest {

    private final GeoPlaceRepository repository = mock(GeoPlaceRepository.class);
    private final GeocodingService service = new GeocodingService(repository, Mappers.getMapper(GeoPlaceMapper.class));

    private final GeoPlace cotonou = GeoPlace.builder().id(UUID.randomUUID()).name("Cotonou").normalizedName("cotonou")
            .region("Littoral").kind(GeoPlaceKind.CITY).lat(6.3703).lng(2.3912).build();
    private final GeoPlace jonquet = GeoPlace.builder().id(UUID.randomUUID()).name("Jonquet").normalizedName("jonquet")
            .region("Littoral").kind(GeoPlaceKind.STATION).parentPlaceId(cotonou.getId()).lat(6.3620).lng(2.4180).build();

    @Test
    void search_returnsExactMatches_withTheirParentCity_resolvedInOneQuery() {
        when(repository.search("jonq", 15)).thenReturn(List.of(jonquet));
        when(repository.findAllById(List.of(cotonou.getId()))).thenReturn(List.of(cotonou));

        List<GeoPlaceResponse> result = service.search(" jonq ");

        assertThat(result).hasSize(1);
        GeoPlaceResponse place = result.get(0);
        assertThat(place.name()).isEqualTo("Jonquet");
        assertThat(place.kind()).isEqualTo(GeoPlaceKind.STATION);
        assertThat(place.parentId()).isEqualTo(cotonou.getId());
        assertThat(place.parentName()).isEqualTo("Cotonou");
        verify(repository, never()).searchFuzzy(eq("jonq"), anyInt());
    }

    @Test
    void search_fallsBackToTrigrams_whenNothingMatchesExactly() {
        when(repository.search("natitngou", 15)).thenReturn(List.of());
        GeoPlace nati = GeoPlace.builder().id(UUID.randomUUID()).name("Natitingou").normalizedName("natitingou")
                .kind(GeoPlaceKind.CITY).lat(10.3042).lng(1.3796).build();
        when(repository.searchFuzzy("natitngou", 15)).thenReturn(List.of(nati));

        List<GeoPlaceResponse> result = service.search("natitngou");

        assertThat(result).extracting(GeoPlaceResponse::name).containsExactly("Natitingou");
        assertThat(result.get(0).parentId()).isNull();
        assertThat(result.get(0).parentName()).isNull();
        verify(repository, never()).findAllById(anyList());
    }

    @Test
    void search_blankQuery_returnsNothing() {
        assertThat(service.search("  ")).isEmpty();
        assertThat(service.search(null)).isEmpty();
        verify(repository, never()).search(eq(""), anyInt());
    }

    @Test
    void listAll_servesTheWholeReferential_parentsResolvedFromTheListItself() {
        when(repository.findAllByOrderByKindAscNameAsc()).thenReturn(List.of(cotonou, jonquet));

        List<GeoPlaceResponse> all = service.listAll();

        assertThat(all).extracting(GeoPlaceResponse::name).containsExactly("Cotonou", "Jonquet");
        assertThat(all.get(1).parentName()).isEqualTo("Cotonou");
        verify(repository, never()).findAllById(anyList());
    }
}
