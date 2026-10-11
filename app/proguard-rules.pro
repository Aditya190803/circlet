# RealBackend is reached by name from AppBootstrap so core-only builds compile
# without the backend modules. Keep it and the container it builds.
-keep class app.circlet.RealBackend { *; }
-keep class app.circlet.AppBootstrap { *; }
-keep class app.circlet.AppContainer { *; }
-keep class app.circlet.BackendSwitch { *; }
-keep class app.circlet.DemoSeed { *; }
# Room entities and DAOs cross the generated-implementation boundary.
-keep class app.circlet.data.db.** { *; }
# Health Connect client calls the provider through its own API surface.
-keep class androidx.health.connect.client.** { *; }
