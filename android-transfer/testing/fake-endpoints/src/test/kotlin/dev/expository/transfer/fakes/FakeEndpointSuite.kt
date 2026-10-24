package dev.expository.transfer.fakes

import dev.expository.transfer.planner.*

object FakeEndpointSuite {
    @JvmStatic fun main(args: Array<String>) {
        val local = SyntheticEndpoint.local()
        val remote = SyntheticEndpoint.remote()
        val drive = SyntheticEndpoint.drive()
        local.addSyntheticName("file.txt")
        remote.addSyntheticName("remote.txt")
        check(local.location.key.kind == LocationKind.LOCAL_SAF)
        check(remote.location.key.kind == LocationKind.REMOTE_SFTP)
        check(drive.location.key.kind == LocationKind.DRIVE_API)
        val upload = OperationPlanner.transfer(
            TransferRequest(listOf(local.item("file.txt")), remote.location,
                destinationListing = remote.listing()), 0L)
        check(upload.executable && upload.route == TransferRoute.DEVICE_RELAY)
        val cloudCopy = OperationPlanner.transfer(
            TransferRequest(listOf(remote.item("remote.txt")), drive.location,
                destinationListing = drive.listing()), 0L)
        check(cloudCopy.executable && cloudCopy.operation == Operation.COPY)
        val cloudMove = OperationPlanner.transfer(
            TransferRequest(listOf(remote.item("remote.txt")), drive.location,
                operation = Operation.MOVE, deletionAuthorized = true,
                destinationListing = drive.listing()), 0L)
        check(!cloudMove.executable && "DRIVE_MOVE_OUT_OF_SCOPE" in cloudMove.blockers)
        drive.addSyntheticName("remote.txt")
        check(!OperationPlanner.revalidate(cloudCopy, TransferRequest(
            listOf(remote.item("remote.txt")), drive.location,
            destinationListing = drive.listing()), 0L))
        println("PASS fake Local/Remote/Drive capability fixtures; E1 synthetic only")
    }
}
