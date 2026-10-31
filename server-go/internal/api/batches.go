package api

import (
	"encoding/json"
	"log/slog"
	"net/http"
	"strconv"
)

type createBatchRequest struct {
	IDs        []int64 `json:"ids"`
	BatchMonth string  `json:"batchMonth"`
}

type addInvoicesRequest struct {
	IDs []int64 `json:"ids"`
}

func (s *Server) handleCreateBatch(w http.ResponseWriter, r *http.Request) {
	var req createBatchRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeErr(w, serverErr(err))
		return
	}
	batch, err := s.Export.CreateBatch(req.IDs, req.BatchMonth)
	if err != nil {
		writeErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, batch)
}

func (s *Server) handleListBatches(w http.ResponseWriter, r *http.Request) {
	page := queryInt(r, "page", 0)
	size := queryInt(r, "size", 20)
	batches, total, err := s.Export.List(page, size)
	if err != nil {
		writeErr(w, serverErr(err))
		return
	}
	writeJSON(w, http.StatusOK, newPage(itemsOrEmpty(batches), page, size, total))
}

func (s *Server) handleBatchInvoices(w http.ResponseWriter, r *http.Request) {
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		writeErr(w, serverErr(err))
		return
	}
	// 批次存在性校验在开流/返回前完成（对齐 Java 控制器）
	if _, err := s.Export.GetBatch(id); err != nil {
		writeErr(w, err)
		return
	}
	invoices, err := s.Export.BatchInvoices(id)
	if err != nil {
		writeErr(w, serverErr(err))
		return
	}
	writeJSON(w, http.StatusOK, itemsOrEmpty(invoices))
}

func (s *Server) handleAddToBatch(w http.ResponseWriter, r *http.Request) {
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		writeErr(w, serverErr(err))
		return
	}
	var req addInvoicesRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeErr(w, serverErr(err))
		return
	}
	batch, err := s.Export.AddInvoices(id, req.IDs)
	if err != nil {
		writeErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, batch)
}

func (s *Server) handleRemoveFromBatch(w http.ResponseWriter, r *http.Request) {
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		writeErr(w, serverErr(err))
		return
	}
	invoiceID, err := strconv.ParseInt(r.PathValue("invoiceId"), 10, 64)
	if err != nil {
		writeErr(w, serverErr(err))
		return
	}
	batch, err := s.Export.RemoveInvoice(id, invoiceID)
	if err != nil {
		writeErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, batch)
}

func (s *Server) handleDeleteBatch(w http.ResponseWriter, r *http.Request) {
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		writeErr(w, serverErr(err))
		return
	}
	restored, err := s.Export.DeleteBatch(id)
	if err != nil {
		writeErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]int{"restored": restored})
}

func (s *Server) handleBatchZip(w http.ResponseWriter, r *http.Request) {
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		writeErr(w, serverErr(err))
		return
	}
	// 先做完所有校验再开流——流一旦开始异常无法再换成错误体
	batch, err := s.Export.GetBatch(id)
	if err != nil {
		writeErr(w, err)
		return
	}
	invoices, err := s.Export.BatchInvoices(id)
	if err != nil {
		writeErr(w, serverErr(err))
		return
	}
	w.Header().Set("Content-Type", "application/zip")
	w.Header().Set("Content-Disposition",
		"attachment; filename*=UTF-8''"+javaURLEncode("发票导出_"+batch.BatchMonth+"_"+strconv.FormatInt(id, 10)+".zip"))
	if err := s.Export.WriteZip(batch, invoices, w); err != nil {
		// 流已开始（头已发出），无法再换错误体——记日志并中断连接（对齐 Java 行为）。
		slog.Error("zip 写流失败", "batchId", id, "err", err)
	}
}
