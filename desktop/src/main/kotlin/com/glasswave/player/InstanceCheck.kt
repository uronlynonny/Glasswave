// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

/**
 * For automated checks only: starts a "first copy", then launches a second one that should step
 * aside and ask the first to show itself. No window opens.
 *   ./gradlew -p desktop instanceCheck -Pdir=<sandbox folder>
 */
fun main(args: Array<String>) {
    if (args.firstOrNull() == "second") {
        println("GW second copy claimed=${SingleInstance.claim()}")
        return
    }
    println("GW first copy claimed=${SingleInstance.claim()}")
    val java = ProcessHandle.current().info().command().get()
    val child = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), "com.glasswave.player.InstanceCheckKt", "second")
        .inheritIO().start()
    child.waitFor()
    Thread.sleep(800)
    println("GW first copy was asked to show itself ${SingleInstance.showRequests} time(s)")
    System.exit(0)
}
