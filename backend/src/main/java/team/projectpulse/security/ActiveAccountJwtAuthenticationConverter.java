package team.projectpulse.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.stereotype.Component;
import team.projectpulse.user.PeerEvaluationUser;
import team.projectpulse.user.UserRepository;

/**
 * Refuses a bearer token whose account has been deactivated or deleted since the token was issued
 * (FR-SEC-active-account).
 *
 * <p>A JWT stays valid for its whole lifetime, and {@code MyUserPrincipal.isEnabled()} is checked only at login.
 * Without this check a deactivated student, or instructor, kept working until her token expired. So every
 * authenticated request loads the user by primary key and throws {@link DisabledException} when she is disabled or
 * gone. The bearer-token filter hands that to {@code CustomBearerTokenAuthenticationEntryPoint}, and
 * {@code ExceptionHandlerAdvice} answers {@code 401}.
 *
 * <p>Authorities are unchanged: the conversion itself is delegated to the existing {@link JwtAuthenticationConverter}
 * bean. This class is deliberately a plain {@code Converter}, not a second {@code JwtAuthenticationConverter} bean,
 * so nothing else picks it up by type; {@code SecurityConfiguration} sets it on the resource server explicitly.
 */
@Component
public class ActiveAccountJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final JwtAuthenticationConverter delegate;
    private final UserRepository userRepository;


    public ActiveAccountJwtAuthenticationConverter(JwtAuthenticationConverter delegate, UserRepository userRepository) {
        this.delegate = delegate;
        this.userRepository = userRepository;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Integer userId = readUserId(jwt);
        boolean active = userId != null && this.userRepository.findById(userId)
                .map(PeerEvaluationUser::isEnabled)
                .orElse(false);
        if (!active) {
            throw new DisabledException("The account of this token is deactivated or no longer exists.");
        }
        return this.delegate.convert(jwt);
    }

    /**
     * Reads the {@code userId} claim the way {@code UserUtils.getUserId} does: the claim may arrive as an Integer,
     * a Long, or a String depending on how it was serialized.
     */
    private static Integer readUserId(Jwt jwt) {
        Object claim = jwt.getClaim("userId");
        if (claim instanceof Integer i) return i;
        if (claim instanceof Long l) return l.intValue();
        if (claim instanceof String s) {
            try {
                return Integer.valueOf(s);
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

}
