package com.mongle.backend.domain.user.withdrawal;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.*;

/** Caller holds users row lock. Statement count is independent of content count; no entity graph loading. */
@Repository @RequiredArgsConstructor
@Transactional(propagation=Propagation.MANDATORY)
public class WithdrawalData {
    private final EntityManager em;

    public boolean hasFinancialRecords(Long user) {
        return count("select count(*) from payment_orders where user_id=:user",user)>0;
    }
    public boolean paymentUnconfirmed(Long user) {
        // READY alone is an unused checkout link. Late valid callbacks are handled against the tombstone.
        return count("select count(*) from payment_orders where user_id=:user and state in ('REQUESTING','UNKNOWN','PENDING')",user)>0;
    }
    public long paidRemaining(Long user) {
        return count("select coalesce(sum(available+reserved),0) from credit_grants where user_id=:user and source_type='PAYMENT'",user);
    }
    public LocalDateTime financialRetention(Long user, LocalDateTime now) {
        var latest=em.createNativeQuery("""
                select max(event_at) from (
                  select max(updated_at) event_at from payment_orders where user_id=:user
                  union all select max(created_at) event_at from payment_credit_reclamations where user_id=:user
                  union all select max(created_at) event_at from credit_ledger where user_id=:user and source_type='PAYMENT'
                ) accounting_dates
                """)
                .setParameter("user",user).getSingleResult();
        LocalDateTime event=latest instanceof java.sql.Timestamp t?t.toLocalDateTime():
                latest instanceof LocalDateTime t?t:now;
        return event.plusYears(5);
    }
    public void deleteContents(Long user, LocalDateTime now) {
        em.flush();
        registerImages(user,now);
        for(String sql:List.of(
                "delete from world_summary_sources where user_id=:user",
                "delete from world_summaries where user_id=:user",
                "delete from world_image_sources where user_id=:user",
                "delete from world_images where user_id=:user",
                "delete from world_composition_entries where user_id=:user",
                "delete from world_compositions where user_id=:user",
                "delete from world_selections where world_id in (select id from world_folders where user_id=:user)",
                "delete from world_folders where user_id=:user",
                "delete from world_bridge_results where user_id=:user",
                "delete from dream_story_versions where story_id in (select id from dream_stories where user_id=:user)",
                "delete from dream_stories where user_id=:user",
                "delete from dream_images where user_id=:user",
                "delete from dream_scene_entities where dream_scene_id in (select id from dream_scenes where user_id=:user)"
                        +" or dream_entity_id in (select id from dream_entities where user_id=:user)",
                "delete from dream_scenes where user_id=:user",
                "delete from dream_entities where user_id=:user",
                "delete from dream_analysis_display_keywords where analysis_id in (select id from dream_analyses where user_id=:user)",
                "delete from dream_analyses where user_id=:user",
                "delete from dream_emotions where dream_id in (select id from dreams where user_id=:user)",
                "delete from dreams where user_id=:user",
                "delete from recent_interpretations where user_id=:user",
                "delete from dream_generation_jobs where user_id=:user",
                "delete from ai_generation_logs where user_id=:user",
                "delete from social_accounts where user_id=:user",
                "delete from refresh_session_tokens where session_id in (select id from refresh_sessions where user_id=:user)",
                "delete from refresh_sessions where user_id=:user"))
            execute(sql,user,now);
        em.clear();
    }
    private List<String> imageSources() {
        var sources=new ArrayList<String>();
        for(String table:List.of("dream_images","world_images")) {
            sources.add("select user_id,id as image_id,storage_key as key_value from "+table
                    +" where user_id=:user and storage_key is not null");
            String prefix=table.equals("dream_images")?"dream-images/":"world-images/";
            for(String ext:List.of(".png",".jpg"))
                sources.add("select user_id,id as image_id,concat('"+prefix+"',user_id,'/',id,'/',attempt_id,'"+ext+"') as key_value from "
                        +table+" where user_id=:user and status='PROCESSING'");
        }
        return sources;
    }
    private void registerImages(Long user, LocalDateTime now) {
        String keys=String.join(" union all ",imageSources());
        // Existing completed/held keys may have been uploaded again. Preserve pending backoff and in-flight ownership.
        execute("update image_cleanup_requests set "
                +"attempt_count=case when status in ('COMPLETED','HELD') then 0 else attempt_count end,"
                +"next_run_at=case when status in ('COMPLETED','HELD') then :now else next_run_at end,"
                +"completed_at=case when status in ('COMPLETED','HELD') then null else completed_at end,"
                +"failure_code=case when status in ('COMPLETED','HELD') then null else failure_code end,"
                +"claim_token=case when status in ('COMPLETED','HELD') then null else claim_token end,"
                +"lease_until=case when status in ('COMPLETED','HELD') then null else lease_until end,"
                +"requeue_requested=case when status='PROCESSING' then true else requeue_requested end,"
                +"version=version+1,status=case when status in ('COMPLETED','HELD') then 'PENDING' else status end "
                +"where user_id=:user and storage_key in (select key_value from ("+keys+") withdrawal_keys)",user,now);
        // Six set-based inserts, not one registration/lookup per image.
        for(String source:imageSources())
            execute("insert into image_cleanup_requests(user_id,image_id,storage_key,reason,created_at,status,attempt_count,next_run_at,requeue_requested,version) "
                    +"select k.user_id,k.image_id,k.key_value,'USER_WITHDRAWN',:now,'PENDING',0,:now,false,0 from ("
                    +source+") k where not exists (select 1 from image_cleanup_requests r where r.storage_key=k.key_value)",user,now);
    }
    public void releaseAndForfeitCredits(Long user, LocalDateTime now) {
        em.flush();
        // Preserve an auditable RELEASE per reservation; running sums produce the same balances as sequential releases.
        execute("""
                insert into credit_ledger(user_id,entry_key,kind,reservation_id,`usage`,available_delta,reserved_delta,available_after,reserved_after,created_at)
                select r.user_id,concat('RELEASE:',r.id),'RELEASE',r.id,r.`usage`,r.amount,-r.amount,
                a.available+sum(r.amount) over(order by r.id rows unbounded preceding),
                a.reserved-sum(r.amount) over(order by r.id rows unbounded preceding),:now
                from credit_reservations r join credit_accounts a on a.user_id=r.user_id
                where r.user_id=:user and r.state='RESERVED'
                """,user,now);
        String allocated="select coalesce(sum(x.amount),0) from credit_allocations x join credit_reservations r on r.id=x.reservation_id"
                +" where x.grant_id=credit_grants.id and r.user_id=:user and r.state='RESERVED'";
        execute("update credit_grants set available=available+("+allocated+"),reserved=reserved-("+allocated+"),version=version+1"
                +" where user_id=:user and reserved>0",user,now);
        execute("update credit_accounts set available=available+reserved,reserved=0,version=version+1,updated_at=:now where user_id=:user",user,now);
        execute("update credit_reservations set state='RELEASED',release_reason='CANCELLED',version=version+1,updated_at=:now"
                +" where user_id=:user and state='RESERVED'",user,now);
        execute("""
                insert into credit_ledger(user_id,entry_key,kind,source_type,grant_id,available_delta,reserved_delta,available_after,reserved_after,created_at)
                select g.user_id,concat('FORFEIT:',g.id),'FORFEIT',g.source_type,g.id,-g.available,0,
                a.available-sum(g.available) over(order by g.id rows unbounded preceding),a.reserved,:now
                from credit_grants g join credit_accounts a on a.user_id=g.user_id
                where g.user_id=:user and g.source_type<>'PAYMENT' and g.available>0
                """,user,now);
        execute("update credit_accounts set available=available-(select coalesce(sum(g.available),0) from credit_grants g"
                +" where g.user_id=:user and g.source_type<>'PAYMENT'),version=version+1,updated_at=:now where user_id=:user",user,now);
        execute("update credit_grants set forfeited=forfeited+available,available=0,version=version+1"
                +" where user_id=:user and source_type<>'PAYMENT' and available>0",user,now);
        em.clear();
    }
    public void deleteAccounting(Long user, LocalDateTime now) {
        em.flush();
        for(String sql:List.of(
                "delete from payment_credit_reclamations where user_id=:user",
                "delete from credit_payment_holds where user_id=:user",
                "delete from payment_notifications where order_id in (select id from payment_orders where user_id=:user)",
                "delete from payment_transaction_bindings where order_id in (select id from payment_orders where user_id=:user)",
                "delete from payment_orders where user_id=:user",
                "delete from credit_ledger where user_id=:user",
                "delete from credit_allocations where reservation_id in (select id from credit_reservations where user_id=:user)",
                "delete from credit_reservations where user_id=:user",
                "delete from credit_grants where user_id=:user",
                "delete from credit_accounts where user_id=:user"))
            execute(sql,user,now);
        em.clear();
    }
    private long count(String sql,Long user) {
        return ((Number)em.createNativeQuery(sql).setParameter("user",user).getSingleResult()).longValue();
    }
    private void execute(String sql,Long user,LocalDateTime now) {
        var q=em.createNativeQuery(sql).setParameter("user",user);
        if(sql.contains(":now")) q.setParameter("now",now);
        q.executeUpdate();
    }
}
