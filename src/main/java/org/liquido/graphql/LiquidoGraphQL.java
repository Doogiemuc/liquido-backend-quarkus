package org.liquido.graphql;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import io.vertx.ext.mail.MailMessage;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.graphql.*;
import org.liquido.security.JwtTokenUtils;
import org.liquido.security.OneTimeToken;
import org.liquido.team.TeamEntity;
import org.liquido.user.UserEntity;
import org.liquido.util.DoogiesUtil;
import org.liquido.util.LiquidoException;

import javax.inject.Inject;
import javax.transaction.Transactional;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@GraphQLApi
@Slf4j
public class LiquidoGraphQL {

    @Inject
    JwtTokenUtils jwtTokenUtils;

    @Inject
    Mailer mailer;

    @ConfigProperty(name = "liquido.frontendUrl")
    String frontendUrl;

    @Query
    @Description("Ping for API")
    public String ping() {
        return "Liquido GraphQL API";
    }

    @Query
    public Optional<TeamEntity> teamByName(@Name("teamName") String teamName) {
        return TeamEntity.find("teamName", teamName).firstResultOptional();
    }

    @Mutation
    @Transactional
    public TeamDataResponse createNewTeam(
        @Name("teamName") String teamName,
        @Name("admin") UserEntity admin
    ) throws LiquidoException {
        admin.setMobilephone(cleanMobilephone(admin.mobilephone));
        admin.setEmail(cleanEmail(admin.email));

        // IF team with same name exist, then throw error
        if (TeamEntity.findByTeamName(teamName).isPresent())
            throw new LiquidoException(LiquidoException.Errors.TEAM_WITH_SAME_NAME_EXISTS, "Cannot create new team: A team with that name ('"+teamName+"') already exists");

        Optional<UserEntity> currentUserOpt = Optional.empty();      //TODO: authUtil.getCurrentUserFromDB();
        boolean emailExists = UserEntity.findByEmail(admin.email).isPresent();
        boolean mobilePhoneExists = UserEntity.findByMobilephone(admin.mobilephone).isPresent();

        if (!currentUserOpt.isPresent()) {
         /* GIVEN an anonymous request (this is what normally happens when a new team is created)
             WHEN anonymous user wants to create a new team
              AND another user with that email or mobile-phone already exists,
             THEN throw an error   */
            if (emailExists) throw new LiquidoException(LiquidoException.Errors.USER_EMAIL_EXISTS, "Sorry, another user with that email already exists.");
            if (mobilePhoneExists) throw new LiquidoException(LiquidoException.Errors.USER_MOBILEPHONE_EXISTS, "Sorry, another user with that mobile phone number already exists.");
        } else {
          /* GIVEN an authenticated request
              WHEN an already registered user wants to create a another new team
               AND he does NOT provide his already registered email and mobile-phone
               AND he does also NOT provide a completely new email and mobilephone
              THEN throw an error */
            boolean providedOwnData = DoogiesUtil.isEqual(currentUserOpt.get().email, admin.email) && DoogiesUtil.isEqual(currentUserOpt.get().mobilephone, admin.mobilephone);
            if (!providedOwnData &&	(emailExists || mobilePhoneExists)) {
                throw new LiquidoException(LiquidoException.Errors.CANNOT_CREATE_TEAM_ALREADY_REGISTERED,
                    "Your are already registered as " + currentUserOpt.get().email + ". You must provide your existing user data or a new email and new mobile phone for the admin of the new team!");
            } else {
                admin = currentUserOpt.get();  // with db ID!
            }
        }


        admin.persistAndFlush();

        TeamEntity team = new TeamEntity(teamName, admin);
        team.persist();
        log.info("Created new team: " + team);

        String jwt = jwtTokenUtils.generateToken(admin.id, team.id);

        TeamDataResponse res = new TeamDataResponse();
        res.team = team;
        res.user = admin;
        res.jwt  = jwt;
        return res;
    }


    /**
     * Clean mobile phone number: Replace everything except plus('+') and number (0-9).
     * Specifically spaces will be removed.
     * This is a very simple thing. Have a look at google phone lib for sophisticated phone number parsing
     * @param mobile a non formatted phone numer
     * @return the cleaned up phone number
     */
    public static String cleanMobilephone(String mobile) {
        if (mobile == null) return null;
        return mobile.replaceAll("[^\\+0-9]", "");
    }

    /**
     * emails a case IN-sensitive. So store and compare them in lowercase
     * @param email an email address
     * @return the email in lowercase
     */
    public static String cleanEmail(String email) {
        if (email == null) return null;
        return email.toLowerCase();
    }

    @Query
    @Transactional
    public String requestEmailToken(@Name("email") String email) throws LiquidoException {
        String emailLowerCase = cleanEmail(email);
        UserEntity user = UserEntity.findByEmail(emailLowerCase)
            .orElseThrow(() -> {
                log.info("Email "+ emailLowerCase +" tried to request email token, but is not registered");
                return new LiquidoException(LiquidoException.Errors.CANNOT_LOGIN_EMAIL_NOT_FOUND, "User with that email <" + emailLowerCase + "> is not found.");
            });

        // Create new email login link with a token time token in it.
        UUID tokenUUID = UUID.randomUUID();
        LocalDateTime validUntil = LocalDateTime.now(); //.plusHours(conf.loginLinkExpirationHours());
        OneTimeToken oneTimeToken = new OneTimeToken(tokenUUID.toString(), user, validUntil);
        oneTimeToken.persist();
        log.info("User " + user.getEmail() + " may login via email link.");

        // This link is parsed in a cypress test case. Must update test if you change this.
        String loginLink = "<a id='loginLink' style='font-size: 20pt;' href='" + frontendUrl + "/login?email="+user.getEmail()+"&emailToken="+oneTimeToken.getNonce()+"'>Login " + user.getName() +  "</a>";
        String body = String.join(
            System.getProperty("line.separator"),
            "<html><h1>Liquido Login Token</h1>",
            "<h3>Hello " + user.getName() + "</h3>",
            "<p>With this link you can login to Liquido.</p>",
            "<p>&nbsp;</p>",
            "<b>" + loginLink + "</b>",
            "<p>&nbsp;</p>",
            "<p>This login link can only be used once!</p>",
            "<p style='color:grey; font-size:10pt;'>You received this email, because a login token for the <a href='https://www.liquido.net'>LIQUIDO</a> eVoting webapp was requested. If you did not request a login yourself, than you may simply ignore this message.</p>",
            "</html>"
        );

        try {
            mailer.send(Mail.withHtml(emailLowerCase, "Login Link for LIQUIDO", body).setFrom("info@liquido.vote"));
        } catch (Exception e) {
            throw new LiquidoException(LiquidoException.Errors.CANNOT_LOGIN_INTERNAL_ERROR, "Internal server error: Cannot send Email " + e.toString(), e);
        }
        return "{ \"message\": \"Email successfully sent.\" }";
    }


}