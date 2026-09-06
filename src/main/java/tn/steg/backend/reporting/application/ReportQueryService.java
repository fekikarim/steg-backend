package tn.steg.backend.reporting.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.steg.backend.common.domain.exception.ResourceNotFoundException;
import tn.steg.backend.organization.domain.model.Department;
import tn.steg.backend.organization.domain.repository.DepartmentRepository;
import tn.steg.backend.reporting.application.dto.GroupCountDto;
import tn.steg.backend.reporting.application.dto.PaymentTotalRowDto;
import tn.steg.backend.reporting.domain.repository.ReportQueryRepository;

import java.util.List;
import java.util.UUID;

/**
 * Read-only reporting use cases for the Back Office dashboards. Every method
 * delegates to a single database-side aggregate; none of them creates, updates
 * or deletes any authoritative state.
 */
@Service
public class ReportQueryService {

    private final ReportQueryRepository reportQueryRepository;
    private final DepartmentRepository departmentRepository;

    public ReportQueryService(ReportQueryRepository reportQueryRepository,
                              DepartmentRepository departmentRepository) {
        this.reportQueryRepository = reportQueryRepository;
        this.departmentRepository = departmentRepository;
    }

    @Transactional(readOnly = true)
    public List<GroupCountDto> applicationsByStatus() {
        return reportQueryRepository.countApplicationsByStatus().stream().map(GroupCountDto::from).toList();
    }

    @Transactional(readOnly = true)
    public List<GroupCountDto> internshipsByType() {
        return reportQueryRepository.countInternshipsByType().stream().map(GroupCountDto::from).toList();
    }

    @Transactional(readOnly = true)
    public List<GroupCountDto> internshipsByStatus() {
        return reportQueryRepository.countInternshipsByStatus().stream().map(GroupCountDto::from).toList();
    }

    @Transactional(readOnly = true)
    public List<GroupCountDto> internshipsByDepartment() {
        return reportQueryRepository.countInternshipsByDepartment().stream().map(GroupCountDto::from).toList();
    }

    @Transactional(readOnly = true)
    public List<GroupCountDto> financeCasesByStatus() {
        return reportQueryRepository.countFinanceCasesByStatus().stream().map(GroupCountDto::from).toList();
    }

    @Transactional(readOnly = true)
    public List<PaymentTotalRowDto> paymentTotals(UUID departmentId) {
        if (departmentId == null) {
            return reportQueryRepository.paymentTotals().stream().map(PaymentTotalRowDto::from).toList();
        }
        if (departmentId != null) {
            departmentRepository.findById(departmentId)
                    .orElseThrow(() -> new ResourceNotFoundException("Department not found: " + departmentId));
        }
        return reportQueryRepository.paymentTotalsForDepartment(departmentId).stream().map(PaymentTotalRowDto::from).toList();
    }
}