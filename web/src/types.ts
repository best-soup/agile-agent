export interface User { id: number; username: string; displayName: string | null }
export interface Project { id: number; name: string; description: string | null; ownerId: number }
export interface Member { userId: number; role: 'OWNER' | 'ADMIN' | 'MEMBER' | 'VIEWER' }
export interface Meeting { id: number; title: string; sourceType: string; indexStatus: string; indexFailureReason: string | null }
export interface DraftItem { id: number; title: string; assigneeName: string | null; description: string | null; priority: string | null; deadline: string | null; sortOrder: number }
export interface Draft { draft: { id: number; status: string; sourceRef: string | null; failureReason: string | null; idempotencyKey: string | null }; items: DraftItem[] }
export interface Task { id: number; title: string; assigneeName: string | null; priority: string | null; deadline: string | null; isCompleted: number }
export interface TaskPage { page: number; size: number; total: number; records: Task[] }
export interface ApiErrorResponse { code: string; message: string; requestId: string | null; fieldErrors: {field: string; message: string}[] }
