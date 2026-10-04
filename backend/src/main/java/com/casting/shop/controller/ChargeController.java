package com.casting.shop.controller;

import com.casting.shop.common.ApiResponse;
import com.casting.shop.domain.Charge;
import com.casting.shop.dto.ChargeAdjustRequest;
import com.casting.shop.dto.ChargeRequest;
import com.casting.shop.dto.ChargeSimulateRequest;
import com.casting.shop.service.ChargeService;
import com.casting.shop.service.ChargeSimulateService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模块二：炉料配料。
 *
 * <p>页面形态为「配比滑杆条」：每个品种一条横向占比条，合计 100% 标红提示，逐项 ± 微调。</p>
 */
@RestController
@RequestMapping("/api/charge")
public class ChargeController {

    private final ChargeService service;
    private final ChargeSimulateService simulateService;

    public ChargeController(ChargeService service, ChargeSimulateService simulateService) {
        this.service = service;
        this.simulateService = simulateService;
    }

    @GetMapping("/board")
    public ApiResponse<Map<String, Object>> board() {
        return ApiResponse.success(service.board());
    }

    @GetMapping("/list")
    public ApiResponse<List<Charge>> list() {
        return ApiResponse.success(service.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.success(service.detail(id));
    }

    @PostMapping
    public ApiResponse<Charge> create(@RequestBody ChargeRequest request) {
        return ApiResponse.success("配料单已建立", service.create(request));
    }

    /** 逐项 ± 微调 */
    @PostMapping("/{id}/adjust")
    public ApiResponse<Charge> adjust(@PathVariable Long id, @RequestBody ChargeAdjustRequest request) {
        return ApiResponse.success("配比已微调", service.adjust(id, request));
    }

    /**
     * 配料偏差双方案推演：以当前配料明细、目标重量、炉体容量、合金材质、回炉料比例为同一份基准，
     * 同步给出「最低补料成本」与「最小成分偏离」两套方案。只读试算，不改动任何业务数据。
     */
    @PostMapping("/simulate")
    public ApiResponse<Map<String, Object>> simulate(@RequestBody ChargeSimulateRequest request) {
        return ApiResponse.success(simulateService.simulate(request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.success("配料单已删除", null);
    }
}
