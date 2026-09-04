import { useNavigate, useSearchParams } from 'react-router'
import { Card } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/misc'
import { ErrorState } from '@/components/ui/states'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { PageContainer, PageHeader } from '@/components/layout/PageContainer'
import { AccountHeaderCard } from '@/features/account/AccountHeaderCard'
import { IdentitySection } from '@/features/account/IdentitySection'
import { PaymentMethodsSection } from '@/features/account/PaymentMethodsSection'
import { PreferencesSection } from '@/features/account/PreferencesSection'
import { VehiclesSection } from '@/features/account/VehiclesSection'
import { useIdentityVerification } from '@/hooks/useAccount'
import { useLogout, useMe } from '@/hooks/useAuth'

const TABS = ['vehicles', 'identity', 'payment', 'preferences'] as const
type TabKey = (typeof TABS)[number]

function isTabKey(value: string | null): value is TabKey {
  return TABS.includes(value as TabKey)
}

/**
 * Mon compte : identite, puis quatre onglets. L'onglet actif vit dans l'URL
 * (?tab=), ce qui permet d'y renvoyer directement (ex. « ajoutez un vehicule »
 * depuis la publication) et de le retrouver apres un retour arriere.
 */
export function MePage() {
  const me = useMe()
  const identity = useIdentityVerification()
  const logout = useLogout()
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
  const tabParam = searchParams.get('tab')
  const tab: TabKey = isTabKey(tabParam) ? tabParam : 'vehicles'

  const user = me.data?.data

  if (me.isError) {
    return (
      <PageContainer width="md">
        <ErrorState onRetry={() => me.refetch()} />
      </PageContainer>
    )
  }

  if (me.isPending || !user) {
    return (
      <PageContainer width="md">
        <PageHeader title="Mon compte" back={false} />
        <Card className="flex items-center gap-4 p-5">
          <Skeleton className="size-16 rounded-full" />
          <div className="flex-1 space-y-2">
            <Skeleton className="h-5 w-40" />
            <Skeleton className="h-4 w-28" />
          </div>
        </Card>
        <Skeleton className="mt-5 h-10 rounded-[var(--radius-control)]" />
        <Skeleton className="mt-4 h-24 rounded-[var(--radius-card)]" />
      </PageContainer>
    )
  }

  return (
    <PageContainer width="md" className="pb-10">
      <PageHeader title="Mon compte" back={false} />

      <AccountHeaderCard user={user} identityStatus={identity.data?.data.status ?? 'NOT_SUBMITTED'} />

      <Tabs
        value={tab}
        onValueChange={(value) => setSearchParams(value === 'vehicles' ? {} : { tab: value }, { replace: true })}
        className="mt-5"
      >
        <TabsList aria-label="Sections du compte">
          <TabsTrigger value="vehicles">Véhicules</TabsTrigger>
          <TabsTrigger value="identity">Identité</TabsTrigger>
          <TabsTrigger value="payment">Paiement</TabsTrigger>
          <TabsTrigger value="preferences">Réglages</TabsTrigger>
        </TabsList>

        <TabsContent value="vehicles">
          <VehiclesSection />
        </TabsContent>
        <TabsContent value="identity">
          <IdentitySection />
        </TabsContent>
        <TabsContent value="payment">
          <PaymentMethodsSection defaultPhone={user.phone} />
        </TabsContent>
        <TabsContent value="preferences">
          <PreferencesSection
            onLogout={() => {
              logout()
              navigate('/', { replace: true })
            }}
          />
        </TabsContent>
      </Tabs>
    </PageContainer>
  )
}
