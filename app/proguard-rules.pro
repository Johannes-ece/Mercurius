# Shizuku creates the user service by class name inside its own shell process.
-keep class de.jvg.mercurius.core.ShellService { <init>(...); *; }
-keep class de.jvg.mercurius.IShellService { *; }
-keep class de.jvg.mercurius.IShellService$* { *; }

# Test entry point run via app_process (see README).
-keep class de.jvg.mercurius.core.TetherControl {
    public static void main(java.lang.String[]);
}
