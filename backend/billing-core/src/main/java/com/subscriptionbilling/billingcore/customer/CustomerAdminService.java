package com.subscriptionbilling.billingcore.customer;

import com.subscriptionbilling.billingcore.subscription.SubscriptionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Read side of the Admin customer-visibility endpoints: a paginated
 * listing and a per-customer detail view that also surfaces the Customer's
 * Subscriptions (via {@link SubscriptionService#listForCustomerAsAdmin}), so an Admin
 * doesn't need a second lookup to go from a Customer to their Subscription ids.
 */
@Service
public class CustomerAdminService {

    private final CustomerRepository customerRepository;
    private final SubscriptionService subscriptionService;

    @Autowired
    public CustomerAdminService(CustomerRepository customerRepository, SubscriptionService subscriptionService) {
        this.customerRepository = customerRepository;
        this.subscriptionService = subscriptionService;
    }

    @Transactional(readOnly = true)
    public CustomerPage list(String cursor, int pageSize) {
        CustomerCursor.Position position = CustomerCursor.decode(cursor);

        List<Customer> rows = customerRepository.findPage(
                position != null ? position.createdAt() : null,
                position != null ? position.id() : null,
                PageRequest.of(0, pageSize + 1));

        boolean hasNextPage = rows.size() > pageSize;
        List<Customer> page = hasNextPage ? rows.subList(0, pageSize) : rows;
        String nextCursor = hasNextPage
                ? CustomerCursor.encode(page.get(page.size() - 1).getCreatedAt(), page.get(page.size() - 1).getId())
                : null;

        return new CustomerPage(page.stream().map(CustomerSummary::from).toList(), nextCursor);
    }

    /**
     * @throws CustomerNotFoundException if {@code customerId} doesn't exist
     */
    @Transactional(readOnly = true)
    public CustomerDetail getById(UUID customerId) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new CustomerNotFoundException(customerId));
        return new CustomerDetail(customer.getId(), customer.getEmail(), customer.getCreatedAt(),
                subscriptionService.listForCustomerAsAdmin(customerId));
    }
}
