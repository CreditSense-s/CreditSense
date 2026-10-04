import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './api'
import type { Analytics, AuditEntry, Decision, Detail, Page, RiskBand, Status, Summary } from './types'

export const keys = {
  mine: ['applications', 'mine'] as const,
  detail: (id: number) => ['applications', id] as const,
  queue: (f: QueueFilters) => ['applications', 'queue', f] as const,
  portfolio: ['portfolio'] as const,
  audit: (f: AuditFilters) => ['audit', f] as const,
}

export const useMyApplications = () =>
  useQuery({ queryKey: keys.mine, queryFn: async () => (await api.get<Summary[]>('/applications/mine')).data })

export const useApplication = (id: number | undefined) =>
  useQuery({
    queryKey: keys.detail(id ?? -1),
    enabled: id != null && !Number.isNaN(id),
    queryFn: async () => (await api.get<Detail>(`/applications/${id}`)).data,
    // waiting for a sleeping model: the server scores it as soon as the model answers, so keep looking
    refetchInterval: (query) => (query.state.data?.awaitingModel ? 10_000 : false),
  })

export interface QueueFilters {
  status: Status[]
  band: RiskBand[]
  q: string
  page: number
  sort: string
}

export const useQueue = (f: QueueFilters) =>
  useQuery({
    queryKey: keys.queue(f),
    placeholderData: keepPreviousData,
    queryFn: async () => {
      const params = new URLSearchParams()
      f.status.forEach((s) => params.append('status', s))
      f.band.forEach((b) => params.append('band', b))
      if (f.q) params.set('q', f.q)
      params.set('page', String(f.page))
      params.set('size', '15')
      params.set('sort', f.sort)
      return (await api.get<Page<Summary>>(`/applications?${params}`)).data
    },
  })

export const usePortfolio = () =>
  useQuery({ queryKey: keys.portfolio, queryFn: async () => (await api.get<Analytics>('/portfolio/analytics')).data })

export interface AuditFilters {
  q: string
  action: string
  entityType: string
  actor: string
  page: number
}

export const useAuditLogs = (f: AuditFilters) =>
  useQuery({
    queryKey: keys.audit(f),
    placeholderData: keepPreviousData,
    queryFn: async () => {
      const params = new URLSearchParams({ page: String(f.page), size: '25', sort: 'createdAt,desc' })
      if (f.q) params.set('q', f.q)
      if (f.action) params.set('action', f.action)
      if (f.entityType) params.set('entityType', f.entityType)
      if (f.actor) params.set('actor', f.actor)
      return (await api.get<Page<AuditEntry>>(`/audit-logs?${params}`)).data
    },
  })

export function useSubmitApplication() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async (body: unknown) => (await api.post<Detail>('/applications', body)).data,
    onSuccess: (d) => {
      qc.setQueryData(keys.detail(d.id), d)
      qc.invalidateQueries({ queryKey: keys.mine })
    },
  })
}

export function useApplicationAction(id: number) {
  const qc = useQueryClient()
  const onSuccess = (d: Detail) => {
    qc.setQueryData(keys.detail(id), d)
    qc.invalidateQueries({ queryKey: ['applications', 'queue'] })
    qc.invalidateQueries({ queryKey: keys.portfolio })
  }
  return {
    decide: useMutation({
      mutationFn: async (body: { decision: Decision; reason?: string }) =>
        (await api.post<Detail>(`/applications/${id}/decision`, body)).data,
      onSuccess,
    }),
    recheck: useMutation({
      mutationFn: async () => (await api.post<Detail>(`/applications/${id}/compliance-check`)).data,
      onSuccess,
    }),
    rescore: useMutation({
      mutationFn: async () => (await api.post<Detail>(`/applications/${id}/risk-assessment`)).data,
      onSuccess,
    }),
  }
}

export function useLifecycleActions() {
  const qc = useQueryClient()
  const refresh = () => qc.invalidateQueries({ queryKey: keys.portfolio })
  return {
    mature: useMutation({
      mutationFn: async (minAgeDays: number) =>
        (await api.post<{ matured: number; defaulted: number; skippedWithoutScore: number }>(
          `/admin/simulate-maturity?minAgeDays=${minAgeDays}`,
        )).data,
      onSuccess: refresh,
    }),
    retrain: useMutation({
      mutationFn: async () =>
        (await api.post<{ promoted: boolean; champion_holdout_auc: number; challenger_holdout_auc: number; serving_version: string }>(
          '/admin/retrain',
          undefined,
          { timeout: 240_000 },
        )).data,
      onSuccess: refresh,
    }),
  }
}
