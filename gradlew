#!/bin/sh

#
# POSIX wrapper launcher: locates java and runs the Gradle wrapper main class.
# (The repository only shipped gradlew.bat; this restores Linux/macOS builds.
# Regenerate with `gradle wrapper` if you want the canonical script.)
#

APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

if [ -n "$JAVA_HOME" ]; then
    JAVACMD=$JAVA_HOME/bin/java
else
    JAVACMD=java
fi

exec "$JAVACMD" -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
