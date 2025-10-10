package org.liquido.security;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;

/**
 * Verify a google login via REST
 */
@Slf4j
public class GoogleLogin {
	private static final String GOOGLE_CLIENT_ID = "673421517010-lkmgt75rsmgua6aojhpp6crjg1opuhvo.apps.googleusercontent.com";

	@POST
	@Path("/auth/google")
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public Response handleGoogleCallback(@QueryParam("code") String code) {
		log.info("received Google callback, code={}", code);
		return Response.ok("received code").build();
		/*

		Exchange "code" for "access_token"
		Validate
		And the TODO: create my JWT response

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
		*/
	}

	private boolean isValidIDToken(String idToken) {
		// Logic to validate the ID token with Google (e.g., using Google's OAuth2 client libraries)    => See below
		return true; // Return true for now as a placeholder
	}

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