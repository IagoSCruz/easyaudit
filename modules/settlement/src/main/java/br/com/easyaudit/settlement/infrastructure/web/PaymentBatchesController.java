package br.com.easyaudit.settlement.infrastructure.web;

import java.time.ZoneOffset;
import java.util.UUID;

import br.com.easyaudit.contracts.api.PaymentBatchesApi;
import br.com.easyaudit.contracts.api.model.BatchCasesRequest;
import br.com.easyaudit.contracts.api.model.BatchStatus;
import br.com.easyaudit.contracts.api.model.CreatePaymentBatchRequest;
import br.com.easyaudit.contracts.api.model.PageMeta;
import br.com.easyaudit.contracts.api.model.PaymentBatchDetail;
import br.com.easyaudit.contracts.api.model.PaymentBatchExport;
import br.com.easyaudit.contracts.api.model.PaymentBatchItem;
import br.com.easyaudit.contracts.api.model.PaymentBatchPage;
import br.com.easyaudit.contracts.api.model.PaymentBatchSummary;
import br.com.easyaudit.contracts.api.model.RegisterPaymentRequest;
import br.com.easyaudit.settlement.application.BatchModel;
import br.com.easyaudit.settlement.application.PaymentBatches;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PaymentBatchesController implements PaymentBatchesApi {

    private final PaymentBatches batches;

    public PaymentBatchesController(PaymentBatches batches) {
        this.batches = batches;
    }

    @Override
    @PreAuthorize("hasRole('FINANCE')")
    public ResponseEntity<PaymentBatchDetail> createPaymentBatch(CreatePaymentBatchRequest request) {
        var detail = batches.create(request.getDueDate(), request.getDescription(), request.getCaseIds(), actor());
        return ResponseEntity.status(HttpStatus.CREATED).body(toDetail(detail));
    }

    @Override
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<PaymentBatchPage> listPaymentBatches(BatchStatus status, String carrierRoot,
            java.time.LocalDate dueFrom, java.time.LocalDate dueTo, Integer page, Integer size) {
        var result = batches.list(status == null ? null : BatchModel.Status.valueOf(status.name()), carrierRoot, dueFrom,
                dueTo, page == null ? 0 : page, size == null ? 20 : size);
        return ResponseEntity.ok(new PaymentBatchPage(result.items().stream().map(PaymentBatchesController::toSummary).toList(),
                new PageMeta(result.page(), result.size(), result.totalElements(), result.totalPages())));
    }

    @Override
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<PaymentBatchDetail> getPaymentBatch(UUID id) {
        return ResponseEntity.ok(toDetail(batches.get(id)));
    }

    @Override
    @PreAuthorize("hasRole('FINANCE')")
    public ResponseEntity<PaymentBatchDetail> addCasesToPaymentBatch(UUID id, BatchCasesRequest request) {
        return ResponseEntity.ok(toDetail(batches.addCases(id, request.getCaseIds(), actor())));
    }

    @Override
    @PreAuthorize("hasRole('FINANCE')")
    public ResponseEntity<PaymentBatchDetail> removeCaseFromPaymentBatch(UUID id, UUID caseId) {
        return ResponseEntity.ok(toDetail(batches.removeCase(id, caseId, actor())));
    }

    @Override
    @PreAuthorize("hasRole('FINANCE')")
    public ResponseEntity<Resource> exportPaymentBatch(UUID id) {
        byte[] csv = batches.export(id, actor());
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("lote-" + id + ".csv").build().toString())
                .body(new ByteArrayResource(csv));
    }

    @Override
    @PreAuthorize("hasRole('FINANCE')")
    public ResponseEntity<PaymentBatchDetail> registerPaymentBatchPayment(UUID id, RegisterPaymentRequest request) {
        return ResponseEntity.ok(toDetail(batches.registerPayment(id, request.getPaidOn(), request.getReference(), actor())));
    }

    @Override
    @PreAuthorize("hasRole('FINANCE')")
    public ResponseEntity<PaymentBatchDetail> cancelPaymentBatch(UUID id) {
        return ResponseEntity.ok(toDetail(batches.cancel(id, actor())));
    }

    // ---- mapeamento

    private static PaymentBatchDetail toDetail(BatchModel.Detail d) {
        return new PaymentBatchDetail(toSummary(d.batch()), d.items().stream().map(PaymentBatchesController::toItem).toList(),
                d.exports().stream().map(e -> new PaymentBatchExport(e.id(), e.exportedAt().withOffsetSameInstant(ZoneOffset.UTC),
                        e.exportedBy(), e.rowCount())).toList());
    }

    private static PaymentBatchSummary toSummary(BatchModel.Batch b) {
        PaymentBatchSummary s = new PaymentBatchSummary(b.id(), BatchStatus.valueOf(b.status().name()), b.carrierRoot(),
                b.dueDate(), b.caseCount(), b.chargedTotal(), b.approvedTotal(), b.savingsTotal(),
                b.createdAt().withOffsetSameInstant(ZoneOffset.UTC), b.createdBy());
        s.setCarrierName(b.carrierName());
        s.setDescription(b.description());
        s.setPaidOn(b.paidOn());
        s.setPaymentReference(b.paymentReference());
        s.setPaidBy(b.paidBy());
        return s;
    }

    private static PaymentBatchItem toItem(BatchModel.Item i) {
        PaymentBatchItem item = new PaymentBatchItem(i.caseId(), i.cteAccessKey(), i.chargedAmount(), i.approvedAmount(),
                i.savingsAmount());
        item.setCteNumber(i.number());
        item.setCteSeries(i.series());
        item.setEmissionDate(i.emissionDate());
        item.setCarrierCnpj(i.carrierCnpj());
        return item;
    }

    static String actor() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken jwt) {
            String username = jwt.getToken().getClaimAsString("preferred_username");
            return username != null ? username : jwt.getToken().getSubject();
        }
        return authentication == null ? "SYSTEM" : authentication.getName();
    }
}
