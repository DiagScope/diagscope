package example.aop;

@Service
public class OrderService {

    public void confirm(String id) {
        persist(id);
        audited(id);
        notifyDownstream(id);
    }

    @Transactional
    public void persist(String id) {
    }

    @Audited
    public void audited(String id) {
    }

    @Async
    private void notifyDownstream(String id) {
    }
}
