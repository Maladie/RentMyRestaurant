package pl.ratemyrestaurant.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.ratemyrestaurant.dao.UserTokenRepository;
import pl.ratemyrestaurant.domain.UserToken;
import pl.ratemyrestaurant.exception.TokenException;
import pl.ratemyrestaurant.model.Info;
import pl.ratemyrestaurant.model.User;
import pl.ratemyrestaurant.model.UserAuthentication;
import pl.ratemyrestaurant.repository.UserRepository;
import pl.ratemyrestaurant.service.TokenAuthenticationService;
import pl.ratemyrestaurant.service.TokenHandlerService;
import pl.ratemyrestaurant.type.TokenStatus;
import pl.ratemyrestaurant.utils.SecurityUtils;
import pl.ratemyrestaurant.utils.Util;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;


@Service
@Transactional
public class TokenAuthenticationServiceImpl implements TokenAuthenticationService {
    private static final String AUTH_HEADER_NAME = "X-XSRF-TOKEN";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserTokenRepository userTokenRepository;

    @Autowired
    private TokenHandlerService tokenHandlerService;

    @Override
    public Authentication getAuthenticationForLogin(HttpServletRequest request, HttpServletResponse response) throws AuthenticationException {
        User user = null;

        String username = "";
        String password = "";
        if (request.getHeader("Content-Type") != null && request.getContentType().equals("application/json")) {

            ObjectMapper mapper = new ObjectMapper();
            try {
               Map<String, String> s= mapper.readValue(request.getReader().lines().collect(Collectors.joining()), Map.class);
               username = s.get("username");
               password = s.get("password");
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            username = request.getParameter("username");
            password = request.getParameter("password");
        }


        UserAuthentication auth = new UserAuthentication(null);

        Info info = auth.getInfo();

        boolean isUsernameEmpty = Util.isNullOrEmpty(username);
        boolean isAuthorized = false;

        if (isUsernameEmpty) {
            info.setCode(100L);
            info.setDesc("User empty username");
        } else {
            if (Util.isNullOrEmpty(password)) {
                info.setCode(101L);
                info.setDesc("User empty password");
            } else {
                user = userRepository.findUserByUsername(username);

                if (Util.isNullObject(user)) {
                    info.setCode(199L);
                    info.setDesc("User not found");
                } else if (Util.isNullOrEmpty(user.getPassword())) {
                    info.setCode(101L);
                    info.setDesc("User missing password");
                } else {
                    if (!SecurityUtils.isPasswordMatch(password, user.getSalt(), user.getPassword())) {
                        // Password
                        // not
                        // equal
                        info.setCode(103L);
                        info.setDesc("User wrong password");
                    } else { // user is authorized successfully
                        isAuthorized = true;
                    }
                }
            }
        }

        if (isAuthorized) {
            info.setCode(0L);
            info.setDesc("Basic");
            auth.setUser(user);
            auth.setAuthenticated(true);
            return auth;
        }

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        auth.setAuthenticated(false);
        return auth;
    }

    @Override
    public Authentication getAuthentication(HttpServletRequest request, HttpServletResponse response) throws IOException {
        final String token = request.getHeader(AUTH_HEADER_NAME);

        UserAuthentication auth = new UserAuthentication(null);

        Info info = auth.getInfo();

        if (!Util.isNullOrEmpty(token)) {
            try {
                User user = tokenHandlerService.parseUserFromToken(token);

                if (!Util.isNullObject(user)) {
                    response.setStatus(HttpServletResponse.SC_OK);
                    auth.setUser(user);
                    auth.setAuthenticated(true);
                    auth.setToken(token);
                    return auth;
                } else {
                    info.setCode(101L);
                    info.setDesc("User not found");
                }
            } catch (TokenException e) {
                info.setCode(102L);
                info.setDesc(e.getDescription());
                info.setObject(e);

                System.out.println("Token Exception : " + e.getMessage());
            } catch (Exception e) {
                info.setCode(103L);
                info.setDesc("AUTHENTICATE_EXCEPTION_RELOGIN_NEEDED");

                System.out.println("Exception" + Arrays.toString(e.getStackTrace()));
            }

        } else {
            info.setCode(100L);
            info.setDesc("Token not found");
        }

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        auth.setAuthenticated(false);
        return auth;
    }

    @Override
    public UserToken addAuthentication(HttpServletResponse response, UserAuthentication authResult) {
        final User user = authResult.getDetails();
        String token = tokenHandlerService.calculateTokenForUser(user);

        UserToken userToken = new UserToken();
        userToken.setUser(user);
        userToken.setToken(token);
        userToken.setStatus(TokenStatus.ACTIVE.getTokenStatus());
        userToken.setCreatedDate(LocalDateTime.now());
        //hopefully removes all tokens cached by user
        userTokenRepository.deactivateAllTokensByUser(user.getId());
        //apply all changes to db instantly
        userTokenRepository.flush();
        userTokenRepository.save(userToken);
        tokenHandlerService.insertToCache(token, user);

        Cookie cookie = new Cookie("XSRF-TOKEN", token);
        cookie.setPath("/");
        cookie.setHttpOnly(false);
        response.addCookie(cookie);

        response.addHeader("X-XSRF-TOKEN", token);
        return userToken;
    }
}
