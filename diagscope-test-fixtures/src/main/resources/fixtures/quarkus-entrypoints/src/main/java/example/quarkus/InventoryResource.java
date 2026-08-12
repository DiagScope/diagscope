package example.quarkus;

@interface Path { String value() default ""; }
@interface GET { }
@interface POST { }
@interface Scheduled { String every() default ""; }

@Path("/inventory")
class InventoryResource {
    private final InventoryService service = new InventoryService();

    @GET
    @Path("/{id}")
    String read(String id) {
        return service.read(id);
    }

    @POST
    String create() {
        return service.create();
    }

    @Path("/internal")
    String internalOnly() {
        return "not an endpoint";
    }
}

class InventoryService {
    String read(String id) { return id; }
    String create() { return "created"; }
}

class InventoryJobs {
    @Scheduled(every = "15s")
    void refresh() { }
}
