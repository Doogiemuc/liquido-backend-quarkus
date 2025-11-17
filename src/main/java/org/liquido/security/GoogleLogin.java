package org.liquido.security;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.Query;
import org.liquido.team.TeamDataResponse;
import org.liquido.user.UserEntity;
import org.liquido.util.LiquidoConfig;
import org.liquido.util.LiquidoException;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Collections;

/**
 * Verify a google login via REST
 */
@Slf4j
@GraphQLApi
public class GoogleLogin {

	@Inject
	LiquidoConfig config;

	@Inject
	JwtTokenUtils jwtTokenUtils;

	@Query
	@Description("Google OneTap login. The passed IdToken will be validated as JWT. If successfull standard LIQUIDO login info will be returned. This contains a LIQUIDO custom JWT. (Which is not the google IdToken!)")
	@PermitAll
	public TeamDataResponse googleOneTapLogin(
			@Name("googleIdToken") @Description("The idToken that was returned by Google oneTapLogin throug our SPA") String googleIdToken
	) throws LiquidoException {
		HttpTransport transport = new NetHttpTransport();
		GsonFactory jsonFactory = GsonFactory.getDefaultInstance();
		GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier.Builder(transport, jsonFactory)
				.setAudience(Collections.singletonList(config.googleClientId()))
				.build();

		GoogleIdToken idToken = null;
		try {
			idToken = verifier.verify(googleIdToken);
		} catch (GeneralSecurityException sece) {
			throw new LiquidoException(LiquidoException.Errors.CANNOT_LOGIN_GOOGLE_IDTOKEN_INVALID, "Cannot do Google OneTap login. Google idToken could not be validated", sece);
		} catch (IOException io) {
			throw new LiquidoException(LiquidoException.Errors.INTERNAL_ERROR, "Cannot do Google OneTap login. Cannot communicate with Google", io);
		}
		if (idToken == null)
			throw new LiquidoException(LiquidoException.Errors.CANNOT_LOGIN_GOOGLE_IDTOKEN_INVALID, "Cannot do Google OneTap login. Google idtoken is not valid");

		// googleIdToken is valid
		GoogleIdToken.Payload payload = idToken.getPayload();

		// Print user identifier. This ID is unique to each Google Account, making it suitable for
		// use as a primary key during account lookup. Email is not a good choice because it can be
		// changed by the user.
		String userId = payload.getSubject();

		// Get profile information from payload
		String email = payload.getEmail();
		boolean emailVerified = payload.getEmailVerified();
		String name = (String) payload.get("name");
		String pictureUrl = (String) payload.get("picture");
		String locale = (String) payload.get("locale");
		String familyName = (String) payload.get("family_name");
		String givenName = (String) payload.get("given_name");

		// Check if user has a verified email and already exists in our LIQUIDO DB
		if (!emailVerified)
				throw new LiquidoException(LiquidoException.Errors.CANNOT_LOGIN_EMAIL_NOT_FOUND, "Cannot Google OneTap Login. User's email is not verified by Google.");
		UserEntity user = UserEntity.findByEmail(email)
				.orElseThrow(LiquidoException.supplyAndLog(LiquidoException.Errors.CANNOT_LOGIN_EMAIL_NOT_FOUND, "Cannot Google OneTap login. Valid idToken, but email not found. User is not yet registred"));

		if (user.picture == null) user.setPicture(pictureUrl);
		log.info("Successful Google OneTap login: googleUserId={} googleEmail={}", userId, email);
		return jwtTokenUtils.doLoginInternal(user, null);
	}



	/**
	 * Google Oauth Authorization Code Flow
	 * This is the version of the flow where our backend is the authorization server. It completely handles the Oauth flow.
	 * Even the Oauth code is never exposed to the frontend
	 * For this flow the redirect_uri
	 * @param code The oauth code that will be exchanged to an access_code and refresh_code
	 * @return a LIQUIDO JWT

	 THIS IS CURRENTLY NOT USED. We use googleOneTapLogin


	@POST
	@Path("/auth/googleCallback")
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public Response handleGoogleCallback(@QueryParam("code") String code) {
		log.debug("received Google callback, code={}", code);
		try {
			// Step 1: Exchange the authorization code for an access token and ID token
			Client client = ClientBuilder.newClient();
			WebTarget target = client.target("https://oauth2.googleapis.com/token");

			// Prepare the form data for token exchange
			Form form = new Form();
			form.param("code", code);
			form.param("client_id", clientId);
			form.param("client_secret", clientSecret);
			form.param("redirect_uri", redirectUri);
			form.param("grant_type", "authorization_code");

			// Make the POST request to Google's token endpoint
			Response response = target.request().post(Entity.form(form));

			// Step 2: Parse the response and extract the ID token
			String responseBody = response.readEntity(String.class);
			JSONObject json = new JSONObject(responseBody);
			String idToken = json.getString("id_token");

			// Step 3: Verify the ID token and authenticate the user
			if (isValidIDToken(idToken)) {
				// Handle the user authentication logic (e.g., create session, issue JWT, etc.)
				return Response.ok("User authenticated successfully").build();
			} else {
				return Response.status(Response.Status.UNAUTHORIZED).entity("Invalid token").build();
			}
		} catch (Exception e) {
			e.printStackTrace();
			return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity("Error during OAuth flow").build();
		}

	}

	*/



	/*
	@POST
	@Path("/auth/google")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@PermitAll
	public Response googleLogin(GoogleLoginRequest request) {
		try {
			if (request.getCredential() == null || request.getCredential().isEmpty()) {
				return Response.status(Response.Status.BAD_REQUEST)
						.entity("{\"error\":\"No credential provided\"}")
						.build();
			}

			// Verify Google ID token
			GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier.Builder(
					new NetHttpTransport(), GsonFactory.getDefaultInstance())
					.setAudience(Collections.singletonList(GOOGLE_CLIENT_ID))
					.build();

			GoogleIdToken idToken = verifier.verify(request.getCredential());
			if (idToken == null) {
				return Response.status(Response.Status.UNAUTHORIZED)
						.entity("{\"error\":\"Invalid Google token\"}")
						.build();
			}

			GoogleIdToken.Payload payload = idToken.getPayload();
			String userId = payload.getSubject();
			String email = payload.getEmail();
			String name = (String) payload.get("name");
			String picture = (String) payload.get("picture");

			// Generate a JWT for our app
			String authToken = Jwt.issuer("LIQUIDO")
					.subject(userId)
					.claim("email", email)
					.claim("name", name)
					.claim("picture", picture)
					.expiresAt(System.currentTimeMillis() / 1000 + 3600) // 1-hour expiry
					.sign();

			return Response.ok(new AuthResponse(authToken, userId, email, name, picture)).build();

		} catch (GeneralSecurityException | IOException e) {
			return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
					.entity("{\"error\":\"Token verification failed\"}")
					.build();
		}
	}

	// Inner class for request body
	@Getter
	public static class GoogleLoginRequest {
		public String credential;
	}

	// Inner class for response body
	public static class AuthResponse {
		public String token;
		public String userId;
		public String email;
		public String name;
		public String picture;

		public AuthResponse(String token, String userId, String email, String name, String picture) {
			this.token = token;
			this.userId = userId;
			this.email = email;
			this.name = name;
			this.picture = picture;
		}
	}

	 */
}