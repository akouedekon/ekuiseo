package bj.ekuiseo.api.mapper;

import bj.ekuiseo.api.domain.Report;
import bj.ekuiseo.api.dto.report.ReportResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface ReportMapper {

    @Mapping(target = "reporterId", source = "reporter.id")
    @Mapping(target = "reportedUserId", source = "reportedUser.id")
    @Mapping(target = "reportedTripId", source = "reportedTrip.id")
    ReportResponse toResponse(Report report);
}
