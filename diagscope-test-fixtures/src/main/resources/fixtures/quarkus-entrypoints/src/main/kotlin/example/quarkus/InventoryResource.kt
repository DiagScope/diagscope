package example.quarkus

annotation class Path(val value: String = "")
annotation class GET
annotation class POST
annotation class Scheduled(val every: String = "")

@Path("/inventory")
class InventoryResource(private val service: InventoryService) {
    @GET
    @Path("/{id}")
    fun read(id: String): String = service.read(id)

    @POST
    fun create(): String = service.create()

    @Path("/internal")
    fun internalOnly(): String = "not an endpoint"
}

class InventoryService {
    fun read(id: String): String = id
    fun create(): String = "created"
}

class InventoryJobs {
    @Scheduled(every = "15s")
    fun refresh() { }
}
