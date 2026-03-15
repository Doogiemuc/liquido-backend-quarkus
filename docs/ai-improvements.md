ou've hit on a very common and important point. While your current implementation with a @RequestScoped bean and manual caching prevents multiple database lookups within a single request, the overall design can be significantly improved by integrating more deeply with Quarkus's security framework. This will not only clean up your code but also make it more robust and easier to maintain.
The key issue is the repetitive injection of JwtTokenUtils and calls to getCurrentUser() in your business logic. A better approach is to make the UserEntity itself directly injectable.
Here is my suggestion to improve this:
Suggested Improvements
I'll implement a solution that uses Quarkus's SecurityIdentityAugmentor to attach your UserEntity to the security identity at the beginning of a request. Then, a CDI producer will make the UserEntity (and TeamEntity) available for direct injection throughout your application.
This has several advantages:
1.
Centralized Logic: The logic for fetching the user is in one place (the augmentor) and is tied to the authentication process.
2.
Clean Code: Your service classes will be cleaner. Instead of injecting JwtTokenUtils and calling a method, you can directly inject the UserEntity.
◦
Before: UserEntity currentUser = jwtTokenUtils.getCurrentUser().orElseThrow(...)
◦
After: @Inject UserEntity currentUser;
3.
Standard Practice: This is the idiomatic way to handle custom user objects in Quarkus, ensuring better integration with the framework and extensions.
4.
Performance: The database lookup happens only once per request, during the augmentation of the security identity.
I will now proceed with the implementation, which involves these steps:
1.
Create a LiquidoSecurityIdentityAugmentor: This class will be responsible for fetching the UserEntity and TeamEntity from the database based on the JWT and attaching them to the SecurityIdentity.
2.
Create a CurrentUserProducer: This CDI producer will make it possible to @Inject the UserEntity and TeamEntity (or an Optional of them) into any of your beans.
3.
Refactor JwtTokenUtils: I will update your existing JwtTokenUtils to use this new mechanism, removing the manual caching and database lookups from getCurrentUser() and getCurrentTeam().
I will start by creating the LiquidoSecurityIdentityAugmentor file.