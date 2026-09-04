package bj.ekuiseo.api.dto.user;

public record UpdateMeRequest(
        String firstName,
        String lastName,
        String email,
        String bio,
        String photoUrl
) {
}
