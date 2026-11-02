# keep javax.mail models
-keep class com.sun.mail.** { *; }
-keep class javax.mail.** { *; }
-keep class javax.activation.** { *; }
-dontwarn java.awt.**
-dontwarn java.beans.Beans
-dontwarn javax.security.sasl.**
