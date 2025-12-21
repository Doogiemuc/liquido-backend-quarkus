package org.liquido.security.webauthn;

import com.yubico.webauthn.CredentialRepository;
import com.yubico.webauthn.RegisteredCredential;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;
import java.util.Set;

@Slf4j
public class CredentialRepoImpl implements CredentialRepository {
	@Override
	public Set<PublicKeyCredentialDescriptor> getCredentialIdsForUsername(String username) {

		return null;
	}

	@Override
	public Optional<ByteArray> getUserHandleForUsername(String s) {
		return Optional.empty();
	}

	@Override
	public Optional<String> getUsernameForUserHandle(ByteArray byteArray) {
		return Optional.empty();
	}

	@Override
	public Optional<RegisteredCredential> lookup(ByteArray byteArray, ByteArray byteArray1) {
		return Optional.empty();
	}

	@Override
	public Set<RegisteredCredential> lookupAll(ByteArray byteArray) {
		return null;
	}

	//adapted from: https://github.com/Yubico/java-webauthn-server/blob/main/webauthn-server-demo/src/main/java/demo/webauthn/InMemoryRegistrationStorage.java

	////////////////////////////////////////////////////////////////////////////////
	// The following methods are required by the CredentialRepository interface.
	////////////////////////////////////////////////////////////////////////////////

	/*

	@Override
	public Set<PublicKeyCredentialDescriptor> getCredentialIdsForUsername(String username) {
		return getRegistrationsByUsername(username).stream()
				.map(
						registration ->
								PublicKeyCredentialDescriptor.builder()
										.id(registration.getCredential().getCredentialId())
										.transports(registration.getTransports())
										.build())
				.collect(Collectors.toSet());
	}

	@Override
	public Optional<String> getUsernameForUserHandle(ByteArray userHandle) {
		return getRegistrationsByUserHandle(userHandle).stream()
				.findAny()
				.map(CredentialRegistration::getUsername);
	}

	@Override
	public Optional<ByteArray> getUserHandleForUsername(String username) {
		return getRegistrationsByUsername(username).stream()
				.findAny()
				.map(reg -> reg.getUserIdentity().getId());
	}

	@Override
	public Optional<RegisteredCredential> lookup(ByteArray credentialId, ByteArray userHandle) {
		Optional<CredentialRegistration> registrationMaybe =
				storage.asMap().values().stream()
						.flatMap(Collection::stream)
						.filter(credReg -> credentialId.equals(credReg.getCredential().getCredentialId()))
						.findAny();

		logger.debug(
				"lookup credential ID: {}, user handle: {}; result: {}",
				credentialId,
				userHandle,
				registrationMaybe);
		return registrationMaybe.map(
				registration ->
						RegisteredCredential.builder()
								.credentialId(registration.getCredential().getCredentialId())
								.userHandle(registration.getUserIdentity().getId())
								.publicKeyCose(registration.getCredential().getPublicKeyCose())
								.signatureCount(registration.getCredential().getSignatureCount())
								.build());
	}

	@Override
	public Set<RegisteredCredential> lookupAll(ByteArray credentialId) {
		return CollectionUtil.immutableSet(
				storage.asMap().values().stream()
						.flatMap(Collection::stream)
						.filter(reg -> reg.getCredential().getCredentialId().equals(credentialId))
						.map(
								reg ->
										RegisteredCredential.builder()
												.credentialId(reg.getCredential().getCredentialId())
												.userHandle(reg.getUserIdentity().getId())
												.publicKeyCose(reg.getCredential().getPublicKeyCose())
												.signatureCount(reg.getCredential().getSignatureCount())
												.build())
						.collect(Collectors.toSet()));
	}

	////////////////////////////////////////////////////////////////////////////////
	// The following methods are specific to this demo application.
	////////////////////////////////////////////////////////////////////////////////

	public boolean addRegistrationByUsername(String username, CredentialRegistration reg) {
		try {
			return storage.get(username, HashSet::new).add(reg);
		} catch (ExecutionException e) {
			logger.error("Failed to add registration", e);
			throw new RuntimeException(e);
		}
	}

	public Collection<CredentialRegistration> getRegistrationsByUsername(String username) {
		try {
			return storage.get(username, HashSet::new);
		} catch (ExecutionException e) {
			logger.error("Registration lookup failed", e);
			throw new RuntimeException(e);
		}
	}

	public Collection<CredentialRegistration> getRegistrationsByUserHandle(ByteArray userHandle) {
		return storage.asMap().values().stream()
				.flatMap(Collection::stream)
				.filter(
						credentialRegistration ->
								userHandle.equals(credentialRegistration.getUserIdentity().getId()))
				.collect(Collectors.toList());
	}

	public void updateSignatureCount(AssertionResult result) {
		CredentialRegistration registration =
				getRegistrationByUsernameAndCredentialId(
						result.getUsername(), result.getCredential().getCredentialId())
						.orElseThrow(
								() ->
										new NoSuchElementException(
												String.format(
														"Credential \"%s\" is not registered to user \"%s\"",
														result.getCredential().getCredentialId(), result.getUsername())));

		Set<CredentialRegistration> regs = storage.getIfPresent(result.getUsername());
		regs.remove(registration);
		regs.add(
				registration.withCredential(
						registration.getCredential().toBuilder()
								.signatureCount(result.getSignatureCount())
								.build()));
	}

	public Optional<CredentialRegistration> getRegistrationByUsernameAndCredentialId(
			String username, ByteArray id) {
		try {
			return storage.get(username, HashSet::new).stream()
					.filter(credReg -> id.equals(credReg.getCredential().getCredentialId()))
					.findFirst();
		} catch (ExecutionException e) {
			logger.error("Registration lookup failed", e);
			throw new RuntimeException(e);
		}
	}

	public boolean removeRegistrationByUsername(
			String username, CredentialRegistration credentialRegistration) {
		try {
			return storage.get(username, HashSet::new).remove(credentialRegistration);
		} catch (ExecutionException e) {
			logger.error("Failed to remove registration", e);
			throw new RuntimeException(e);
		}
	}

	public boolean removeAllRegistrations(String username) {
		storage.invalidate(username);
		return true;
	}

	public boolean userExists(String username) {
		return !getRegistrationsByUsername(username).isEmpty();
	}


	 */

}